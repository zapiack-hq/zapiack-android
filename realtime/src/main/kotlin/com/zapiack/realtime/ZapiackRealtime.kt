package com.zapiack.realtime

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.random.Random

/*
 * Zapiack Realtime: live messages over one WebSocket.
 *
 * Your server mints a short-lived token with its API key (POST /v1/realtime/tokens) and hands it to the app, so the API key never reaches
 * the device. The client asks for a token every time it connects, so one that expires is simply replaced.
 *
 * It reconnects by itself with a growing, jittered pause, re-joins every channel and asks for what it missed since the last persisted
 * message it saw, so a lost signal costs no messages.
 *
 * Everything runs on one background thread, so frames go out in the order they were asked for. Callbacks arrive on that thread too:
 * hand work to your main thread before touching views.
 */

enum class RealtimeState { IDLE, CONNECTING, CONNECTED, RECONNECTING, CLOSED }

data class RealtimeMessage(
    val msgId: String,
    val ts: Double,
    val channel: String,
    val event: String,
    /** JSON the way org.json reads it: JSONObject, JSONArray, String, Number, Boolean or null. */
    val data: Any?,
    /** The user id (or connection id) of the sender. */
    val from: String?,
    /** True when it was sent while this client was away and is being replayed. */
    val replayed: Boolean,
)

data class PresenceMember(val connId: String, val user: String?, val data: Any?)

data class PublishResult(val msgId: String, val delivered: Int)

/** [code] is the server's: 401 token, 403 not allowed, 410 could not replay, 413 too large, 429 slow down, 503 try again. 0 is a problem on this side. */
class RealtimeException(val code: Int, message: String, /** For a failed [ZapiackRealtime.request]: the service's own word for it: `forbidden`, `invalid`, `no_route`, `timeout`, `rate_limited` or `internal`. */ val reason: String? = null) : Exception(message)

/** Asks your server for a token. Called off the main thread, on every connect, so it may block on the network. */
fun interface TokenProvider {
    @Throws(Exception::class)
    fun token(): String
}

interface RealtimeSocketListener {
    fun onMessage(text: String)
    fun onClosed(code: Int)
}

/** One open line to the server. The default is OkHttp's; tests give a fake. */
interface RealtimeSocket {
    fun send(text: String)
    fun close()
}

fun interface RealtimeSocketFactory {
    fun open(url: String, listener: RealtimeSocketListener): RealtimeSocket
}

class RealtimeSubscription internal constructor(private val stop: () -> Unit) {
    /** Stops this listener, and leaves the channel when it was the last. */
    fun cancel() = stop()
}

class ZapiackRealtime @JvmOverloads constructor(
    private val tokens: TokenProvider,
    private val url: String = DEFAULT_URL,
    private val heartbeatMs: Long = 25_000,
    private val backoffMinMs: Long = 1_000,
    private val backoffMaxMs: Long = 30_000,
    private val ackTimeoutMs: Long = 10_000,
    private val sockets: RealtimeSocketFactory = OkHttpSocketFactory(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        const val DEFAULT_URL = "wss://rt.zapiack.com"
        private const val MAX_OUTBOX = 100
    }

    private val exec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "zapiack-realtime").apply { isDaemon = true } }
    private val tokenExec = Executors.newCachedThreadPool { r -> Thread(r, "zapiack-realtime-token").apply { isDaemon = true } }

    @Volatile var state: RealtimeState = RealtimeState.IDLE
        private set
    /** This connection's id, once connected. Other members use it to signal you. */
    @Volatile var connectionId: String? = null
        private set
    var onState: ((RealtimeState) -> Unit)? = null

    /** Another listener for state changes, for code that must not take [onState] from the app (a call uses this). */
    fun addStateListener(fn: (RealtimeState) -> Unit): RealtimeSubscription {
        val id = synchronized(this) { ++nextId }
        exec.execute { stateListeners[id] = fn }
        return RealtimeSubscription { exec.execute { stateListeners.remove(id) } }
    }
    var onError: ((RealtimeException) -> Unit)? = null

    private class Sub(var cursor: String?) {
        val handlers = LinkedHashMap<Int, (RealtimeMessage) -> Unit>()
    }

    private var socket: RealtimeSocket? = null
    private var generation = 0
    private var wanted = false
    private var refused = false
    private var attempt = 0
    private var heard = 0L
    private var nextId = 0
    private var ackSeq = 0
    private var beat: ScheduledFuture<*>? = null
    private var retry: ScheduledFuture<*>? = null
    private val outbox = ArrayList<String>()
    private val subs = LinkedHashMap<String, Sub>()
    private val waiting = HashMap<String, Pair<(Result<PublishResult>) -> Unit, ScheduledFuture<*>>>()
    private val requests = HashMap<String, Pair<(Result<JSONObject>) -> Unit, ScheduledFuture<*>>>()
    private val stateListeners = LinkedHashMap<Int, (RealtimeState) -> Unit>()
    private var rpcSeq = 0
    private val first = ArrayList<(Result<Unit>) -> Unit>()
    private val members = HashMap<String, LinkedHashMap<String, PresenceMember>>()
    private val presenceHandlers = HashMap<String, LinkedHashMap<Int, (String, PresenceMember) -> Unit>>()

    // ───────────────────────────── public

    /** Connects; [callback] is told once the server has said hello (or that the project refuses this client). It then keeps itself connected until [disconnect]. */
    @JvmOverloads
    fun connect(callback: ((Result<Unit>) -> Unit)? = null) = exec.execute {
        if (state == RealtimeState.CONNECTED) return@execute callback?.invoke(Result.success(Unit)) ?: Unit
        wanted = true
        if (callback != null) first += callback
        if (socket == null && retry == null) open()
    }

    /** Leaves for good: closes the line and drops what was waiting. Subscriptions stay, for a later [connect]. */
    fun disconnect() = exec.execute {
        wanted = false
        generation++
        retry?.cancel(false); retry = null
        stopBeat()
        socket?.close(); socket = null
        connectionId = null
        failAll(RealtimeException(0, "Disconnected."))
        outbox.clear()
        set(RealtimeState.CLOSED)
    }

    /** Calls [handler] for every message on the channel, now and after reconnects. */
    @JvmOverloads
    fun subscribe(channel: String, resumeFrom: String? = null, handler: (RealtimeMessage) -> Unit): RealtimeSubscription {
        val id = synchronized(this) { ++nextId }
        exec.execute {
            val fresh = subs[channel] == null
            val sub = subs.getOrPut(channel) { Sub(resumeFrom) }
            sub.handlers[id] = handler
            if (fresh) enqueue(subscribeFrame(channel, sub.cursor))
        }
        return RealtimeSubscription {
            exec.execute {
                val sub = subs[channel] ?: return@execute
                sub.handlers.remove(id)
                if (sub.handlers.isEmpty()) {
                    subs.remove(channel); presenceHandlers.remove(channel); members.remove(channel)
                    enqueue(JSONObject().put("op", "unsubscribe").put("channel", channel))
                }
            }
        }
    }

    /**
     * Sends a message. With a [callback] it is told when the server confirms (or why it refused); without one it is fire and forget.
     * [persist] keeps it in the channel's history so clients that were away can replay it.
     */
    @JvmOverloads
    fun publish(channel: String, data: Any?, event: String = "message", persist: Boolean = false, callback: ((Result<PublishResult>) -> Unit)? = null) = exec.execute {
        val frame = JSONObject().put("op", "publish").put("channel", channel).put("event", event).put("data", json(data)).put("persist", persist)
        if (callback == null) {
            enqueue(frame)
            return@execute
        }
        val ackId = "a${++ackSeq}"
        frame.put("ackId", ackId)
        val timer = exec.schedule({ settle(ackId)?.invoke(Result.failure(RealtimeException(0, "The server did not confirm in time."))) }, ackTimeoutMs, TimeUnit.MILLISECONDS)
        waiting[ackId] = callback to timer
        if (!enqueue(frame)) settle(ackId)?.invoke(Result.failure(RealtimeException(429, "Could not send: too many messages waiting for a connection.")))
    }

    /**
     * Asks a Zapiack service something over this connection and tells [callback] the answer: the way a call client talks to the media
     * server. Needs a token minted for a call. It is never queued for later: if the line is down it fails at once, because an answer to
     * an old question is worse than none. A failure carries the service's word for it in [RealtimeException.reason].
     */
    fun request(method: String, data: Map<String, Any?> = emptyMap(), callback: (Result<JSONObject>) -> Unit) = exec.execute {
        val s = socket
        if (s == null || state != RealtimeState.CONNECTED) return@execute callback(Result.failure(RealtimeException(0, "Not connected.", "no_route")))
        val id = "r${++rpcSeq}"
        val timer = exec.schedule({ requests.remove(id)?.first?.invoke(Result.failure(RealtimeException(0, "The server did not answer in time.", "timeout"))) }, ackTimeoutMs + 2_000, TimeUnit.MILLISECONDS)
        requests[id] = callback to timer
        s.send(JSONObject().put("op", "rpc").put("method", method).put("correlationId", id).put("data", json(data)).toString())
    }

    /** Sends to one connection in a channel you are subscribed to. Used for peer-to-peer setup such as WebRTC. */
    fun signal(channel: String, to: String, event: String, data: Any?) = exec.execute {
        enqueue(JSONObject().put("op", "signal").put("channel", channel).put("to", to).put("event", event).put("data", json(data)))
    }

    /** Presence in a channel you are subscribed to. */
    fun presence(channel: String) = Presence(channel)

    inner class Presence internal constructor(private val channel: String) {
        fun enter(data: Any? = null) = exec.execute { enqueue(JSONObject().put("op", "presence.enter").put("channel", channel).put("data", json(data))) }
        fun update(data: Any?) = exec.execute { enqueue(JSONObject().put("op", "presence.update").put("channel", channel).put("data", json(data))) }
        fun leave() = exec.execute { enqueue(JSONObject().put("op", "presence.leave").put("channel", channel)) }

        /** Who is here now, as last told. */
        fun members(): List<PresenceMember> = exec.submit<List<PresenceMember>> { members[channel]?.values?.toList() ?: emptyList() }.get()

        /** Called with "enter", "update" or "leave" and the member. */
        fun onChange(fn: (String, PresenceMember) -> Unit): RealtimeSubscription {
            val id = synchronized(this@ZapiackRealtime) { ++nextId }
            exec.execute { presenceHandlers.getOrPut(channel) { LinkedHashMap() }[id] = fn }
            return RealtimeSubscription { exec.execute { presenceHandlers[channel]?.remove(id) } }
        }
    }

    // ───────────────────────────── internals (all on the one thread)

    private fun set(s: RealtimeState) {
        if (state == s) return
        state = s
        onState?.invoke(s)
        for (l in stateListeners.values.toList()) runCatching { l(s) }
    }

    private fun fail(e: RealtimeException) {
        onError?.invoke(e)
    }

    private fun settle(ackId: String): ((Result<PublishResult>) -> Unit)? {
        val (cb, timer) = waiting.remove(ackId) ?: return null
        timer.cancel(false)
        return cb
    }

    private fun failAll(e: RealtimeException) {
        for (id in waiting.keys.toList()) settle(id)?.invoke(Result.failure(e))
        failRequests(e)
    }

    private fun failRequests(e: RealtimeException) {
        for (id in requests.keys.toList()) requests.remove(id)?.let { (cb, timer) -> timer.cancel(false); cb(Result.failure(e)) }
    }

    private fun subscribeFrame(channel: String, cursor: String?) = JSONObject().put("op", "subscribe").put("channel", channel).apply { if (cursor != null) put("resumeFrom", cursor) }

    /** Writes now if the line is open, otherwise keeps it (a short while) for when it is. False when there is no room. */
    private fun enqueue(frame: JSONObject): Boolean {
        val s = socket
        if (s != null && state == RealtimeState.CONNECTED) {
            s.send(frame.toString())
            return true
        }
        // A subscribe is re-sent from the subscriptions when the line opens, so queueing it too would only say it twice.
        val op = frame.optString("op")
        if (op == "subscribe" || op == "unsubscribe") return true
        if (outbox.size >= MAX_OUTBOX) return false
        outbox += frame.toString()
        return true
    }

    private fun open() {
        if (!wanted) return
        val gen = ++generation
        set(if (attempt == 0 && state != RealtimeState.RECONNECTING) RealtimeState.CONNECTING else RealtimeState.RECONNECTING)
        tokenExec.execute {
            val result = runCatching { tokens.token().also { require(it.isNotEmpty()) { "getToken returned no token." } } }
            exec.execute {
                if (!wanted || gen != generation) return@execute
                val token = result.getOrElse {
                    fail(RealtimeException(0, "Could not get a token: ${it.message}"))
                    return@execute later()
                }
                val full = "${url.trimEnd('/')}/?token=${java.net.URLEncoder.encode(token, "UTF-8").replace("+", "%20")}"
                heard = clock()
                socket = sockets.open(
                    full,
                    object : RealtimeSocketListener {
                        override fun onMessage(text: String) = exec.execute { onFrame(text, gen) }
                        override fun onClosed(code: Int) = exec.execute { onClose(code, gen) }
                    },
                )
            }
        }
    }

    private fun onFrame(text: String, gen: Int) {
        if (gen != generation) return
        heard = clock()
        val f = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (f.optString("op")) {
            "hello" -> {
                connectionId = f.optString("connId")
                attempt = 0
                set(RealtimeState.CONNECTED)
                for ((channel, sub) in subs) socket?.send(subscribeFrame(channel, sub.cursor).toString())
                outbox.forEach { socket?.send(it) }
                outbox.clear()
                startBeat(gen)
                val callbacks = first.toList(); first.clear()
                callbacks.forEach { it(Result.success(Unit)) }
            }
            "message" -> onMessage(f)
            "ack" -> settle(f.optString("ackId"))?.invoke(Result.success(PublishResult(f.optString("msgId"), f.optInt("delivered", 0))))
            "error" -> {
                if (f.optBoolean("fatal", false)) refused = true
                val e = RealtimeException(f.optInt("code", 0), f.optString("message", "Error"))
                val ref = f.optString("ref", "")
                val cb = if (ref.isNotEmpty()) settle(ref) else null
                if (cb != null) cb(Result.failure(e)) else fail(e)
            }
            "rpc.result", "rpc.error" -> {
                val (cb, timer) = requests.remove(f.optString("correlationId")) ?: return
                timer.cancel(false)
                if (f.optString("op") == "rpc.result") cb(Result.success(f.optJSONObject("data") ?: JSONObject()))
                else cb(Result.failure(RealtimeException(0, f.optString("message", "The request failed."), f.opt("code")?.toString() ?: "internal")))
            }
            "presence.members" -> {
                val map = LinkedHashMap<String, PresenceMember>()
                val list = f.optJSONArray("members") ?: JSONArray()
                for (i in 0 until list.length()) list.optJSONObject(i)?.let { member(it)?.let { m -> map[m.connId] = m } }
                members[f.optString("channel")] = map
            }
            // Too long away to replay: the app should reload what it needs over REST. Say so rather than hide the gap.
            "resume.failed" -> fail(RealtimeException(410, "Missed messages on ${f.optString("channel")} could not be replayed. Reload its history."))
        }
    }

    private fun onMessage(f: JSONObject) {
        val channel = f.optString("channel")
        val msgId = f.optString("msgId")
        val event = f.optString("event")
        if (event.startsWith("presence.")) return onPresenceMessage(channel, event, f)
        val sub = subs[channel] ?: return
        if (f.optBoolean("persisted", false)) {
            // Seen already (a replay overlapping a live message): once is enough.
            val cursor = sub.cursor
            if (cursor != null && !isAfter(msgId, cursor)) return
            sub.cursor = msgId
        }
        val data = f.opt("data").let { if (it == JSONObject.NULL) null else it }
        val from = if (f.isNull("from")) null else f.optString("from")
        val message = RealtimeMessage(msgId, f.optDouble("ts", 0.0), channel, event, data, from, f.optBoolean("replayed", false))
        for (h in sub.handlers.values.toList()) runCatching { h(message) }
    }

    private fun onPresenceMessage(channel: String, event: String, f: JSONObject) {
        val m = f.optJSONObject("data")?.let { member(it) } ?: return
        val action = event.removePrefix("presence.")
        if (action == "leave") members[channel]?.remove(m.connId) else members.getOrPut(channel) { LinkedHashMap() }[m.connId] = m
        for (h in presenceHandlers[channel]?.values?.toList() ?: emptyList()) runCatching { h(action, m) }
    }

    private fun member(o: JSONObject): PresenceMember? {
        val id = o.optString("connId", "")
        if (id.isEmpty()) return null
        val data = o.opt("data").let { if (it == JSONObject.NULL) null else it }
        return PresenceMember(id, if (o.isNull("user")) null else o.optString("user"), data)
    }

    private fun startBeat(gen: Int) {
        stopBeat()
        beat = exec.scheduleWithFixedDelay({
            if (gen != generation) return@scheduleWithFixedDelay
            // Nothing heard for two beats: the line is dead even if the socket says otherwise. Drop it and start again.
            if (clock() - heard > heartbeatMs * 2) {
                socket?.close()
                onClose(4000, gen)
            } else {
                socket?.send("""{"op":"ping"}""")
            }
        }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS)
    }

    private fun stopBeat() {
        beat?.cancel(false)
        beat = null
    }

    private fun onClose(code: Int, gen: Int) {
        if (gen != generation) return
        generation++
        failRequests(RealtimeException(0, "The connection dropped.", "no_route"))
        socket = null
        stopBeat()
        connectionId = null
        if (!wanted) return
        // 4403, or an error the server marked fatal: the project switched this client off, and trying again would only be refused again.
        if (code == 4403 || refused) {
            refused = false
            wanted = false
            val e = RealtimeException(403, "The project does not allow this connection.")
            failAll(e)
            val callbacks = first.toList(); first.clear()
            callbacks.forEach { it(Result.failure(e)) }
            set(RealtimeState.CLOSED)
            return
        }
        set(RealtimeState.RECONNECTING)
        // 4401 means the token was refused or ran out: fetching a new one is exactly what the next attempt does, so there is no pause.
        later(if (code == 4401 || code == 1012) 0 else null)
    }

    private fun later(force: Long? = null) {
        retry?.cancel(false)
        attempt++
        val ceiling = min(backoffMaxMs, backoffMinMs shl min(attempt - 1, 20))
        // Full jitter, so a node that restarts is not met by every client at the same moment.
        val wait = force ?: (backoffMinMs + (Random.nextDouble() * (ceiling - backoffMinMs)).toLong())
        set(RealtimeState.RECONNECTING)
        retry = exec.schedule({ retry = null; open() }, wait, TimeUnit.MILLISECONDS)
    }

    private fun json(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject(v.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JSONArray(v.map { json(it) })
        else -> JSONObject.wrap(v) ?: v.toString()
    }
}

/** Message ids are "milliseconds-sequence"; this orders them. */
internal fun isAfter(a: String, b: String): Boolean {
    fun parts(s: String) = s.split("-").map { it.toLongOrNull() ?: 0L }.let { it.getOrElse(0) { 0L } to it.getOrElse(1) { 0L } }
    val (am, aseq) = parts(a)
    val (bm, bseq) = parts(b)
    return am > bm || (am == bm && aseq > bseq)
}
