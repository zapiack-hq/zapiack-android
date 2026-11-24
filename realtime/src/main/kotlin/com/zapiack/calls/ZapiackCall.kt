package com.zapiack.calls

import com.zapiack.realtime.RealtimeException
import com.zapiack.realtime.RealtimeMessage
import com.zapiack.realtime.RealtimeState
import com.zapiack.realtime.RealtimeSubscription
import com.zapiack.realtime.ZapiackRealtime
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/*
 * A call: join a room, send the microphone, camera and screen, and receive everyone else's.
 *
 * Your server makes the room and a token for each person (POST /v1/calls/rooms, POST /v1/calls/tokens, or POST /v1/calls to ring someone)
 * and gives the token to the app, so the API key never reaches the phone.
 *
 *     val call = ZapiackCalls.create(context) { myServer.callToken() }
 *     call.onTrack = { t -> show(t) }
 *     call.join()
 *     call.enableMicrophone()
 *     call.enableCamera()
 *
 * What a person may send comes from their token: a listener can only receive. The same signalling and the same server requests as the
 * web, React Native and Flutter clients.
 *
 * The functions that talk to the server are `suspend`: call them from a coroutine. Callbacks arrive on one background thread, so hand
 * work to your main thread before touching views.
 */

/** What a track is: a microphone, a camera, or a shared screen. */
enum class TrackSource { AUDIO, VIDEO, SCREEN;
    val wire: String get() = name.lowercase()
}

enum class CallRole { HOST, SPEAKER, LISTENER }

enum class CallState { IDLE, JOINING, JOINED, RECONNECTING, CLOSED }

data class CallPeer(val identity: String, val role: CallRole)

/** Someone else's audio, video or screen. [track]'s `native` is the engine's own track: give it to a video view; audio plays by itself. */
data class RemoteTrack(val identity: String, val source: TrackSource, val track: MediaTrack, val producerId: String)

data class TrackEnded(val identity: String, val source: TrackSource, val producerId: String)

data class TrackPaused(val identity: String, val source: TrackSource, val producerId: String, val paused: Boolean)

data class CallStats(val identity: String, val source: TrackSource, val bytesReceived: Long, val packetsReceived: Long, val packetsLost: Long, val jitter: Double)

/** [reason] is the service's word for it when the server refused: `forbidden`, `invalid`, `rate_limited`... */
class CallException(message: String, val reason: String? = null) : Exception(message)

/** What a call needs from the phone besides the connection: the microphone, the camera, the screen and the speaker. */
interface CallMedia {
    suspend fun microphone(): MediaTrack

    /** The front camera when [front] is true, otherwise the back one. */
    suspend fun camera(front: Boolean): MediaTrack

    /** Asks the person to share their screen. */
    suspend fun screen(): MediaTrack
    fun setSpeaker(on: Boolean)
    fun switchCamera(track: MediaTrack)
}

/** The signalling line, as a call uses it. [RealtimeSignal] is the real one; tests give a fake. */
interface CallSignal {
    suspend fun connect()
    fun disconnect()
    suspend fun request(method: String, data: Map<String, Any?> = emptyMap()): Map<String, Any?>
    fun subscribe(channel: String, handler: (RealtimeMessage) -> Unit): RealtimeSubscription
    fun addStateListener(fn: (RealtimeState) -> Unit): RealtimeSubscription
}

class RealtimeSignal(private val rt: ZapiackRealtime) : CallSignal {
    override suspend fun connect() = suspendCancellableCoroutine<Unit> { c ->
        rt.connect { r -> r.fold({ c.resume(Unit) }, { c.resumeWithException(it) }) }
    }

    override fun disconnect() = rt.disconnect()

    override suspend fun request(method: String, data: Map<String, Any?>): Map<String, Any?> = suspendCancellableCoroutine { c ->
        rt.request(method, data) { r ->
            r.fold(
                { c.resume(normalize(it).obj()) },
                { c.resumeWithException(if (it is RealtimeException) CallException(it.message ?: "The request failed.", it.reason) else it) },
            )
        }
    }

    override fun subscribe(channel: String, handler: (RealtimeMessage) -> Unit): RealtimeSubscription = rt.subscribe(channel, null, handler)
    override fun addStateListener(fn: (RealtimeState) -> Unit): RealtimeSubscription = rt.addStateListener(fn)
}

private class Consumed(val consumer: Consumer, val identity: String, val source: TrackSource)

private fun roleOf(v: Any?) = CallRole.entries.firstOrNull { it.name.equals(v as? String, ignoreCase = true) } ?: CallRole.SPEAKER
private fun sourceOf(v: Any?) = TrackSource.entries.firstOrNull { it.wire == v } ?: TrackSource.VIDEO

class ZapiackCall(
    private val signal: CallSignal,
    factory: LinkFactory,
    private val media: CallMedia,
    /** Send and receive over TCP only, for networks that block UDP. */
    private val forceTcp: Boolean = false,
    frontCamera: Boolean = true,
    /** How long a path that reports `disconnected` gets to come back before it is restarted. A phone changing from Wi-Fi to mobile data goes through it. */
    private val disconnectedGraceMs: Long = 4_000,
    /** How often the call asks the server who is in the room and catches up on anything it missed. 0 turns it off. */
    private val resyncMs: Long = 30_000,
) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "zapiack-call").apply { isDaemon = true } }
    private val dispatcher = exec.asCoroutineDispatcher()
    // Whatever goes wrong in the background (a malformed event, a refused request) is reported through `onError`. Left alone, an exception in a
    // background coroutine would reach the thread's uncaught-exception handler, which on Android ends the app.
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> error(e) })
    private val device = Device(factory)

    private var sendT: Transport? = null
    private var recvT: Transport? = null
    private val producers = LinkedHashMap<TrackSource, Producer>()
    private val consumers = LinkedHashMap<String, Consumed>()
    private val peers = LinkedHashMap<String, CallPeer>()
    private val fetching = HashSet<String>()
    private val lapse = HashMap<String, Job>()
    private var joined: Map<String, Any?>? = null
    private var roomSub: RealtimeSubscription? = null
    private var stateSub: RealtimeSubscription? = null
    private var wasConnected = false
    private var resyncing = false
    private var resyncJob: Job? = null
    private var speaker = false
    private var front = frontCamera

    @Volatile var state: CallState = CallState.IDLE
        private set

    /** Someone's track arrived. */
    var onTrack: ((RemoteTrack) -> Unit)? = null

    /** Someone stopped sending a track, or left. */
    var onTrackEnded: ((TrackEnded) -> Unit)? = null

    /** Someone muted or unmuted a track. The track stays; what it carries is silence or a frozen picture. */
    var onPaused: ((TrackPaused) -> Unit)? = null
    var onPeerJoined: ((CallPeer) -> Unit)? = null
    var onPeerLeft: ((String) -> Unit)? = null

    /** The room was ended, or the line was lost for good. The call is already closed when this fires. */
    var onEnded: ((String) -> Unit)? = null

    /** A problem worth showing: a track that could not be played, or a refused request. */
    var onError: ((CallException) -> Unit)? = null
    var onStateChange: ((CallState) -> Unit)? = null

    val role: CallRole? get() = joined?.get("role")?.let { roleOf(it) }
    val roomId: String? get() = joined?.get("roomId") as? String

    /** Who else is in the room now. */
    val members: List<CallPeer> get() = synchronized(peers) { peers.values.toList() }

    /** What is being sent now. */
    val sending: List<TrackSource> get() = synchronized(producers) { producers.keys.toList() }

    private fun set(s: CallState) {
        state = s
        onStateChange?.invoke(s)
    }

    private fun emit(fn: () -> Unit) { runCatching(fn) }

    private fun error(e: Throwable) = emit { onError?.invoke(e as? CallException ?: CallException(e.message ?: e.toString())) }

    /** Connects, joins the room the token is for, and starts receiving what the others are sending. */
    suspend fun join(): Triple<CallRole, String, List<CallPeer>> = withContext(dispatcher) {
        if (state == CallState.JOINED) return@withContext Triple(role!!, roomId!!, members)
        set(CallState.JOINING)
        try {
            signal.connect()
            val reply = signal.request("media.join")
            joined = reply
            if (!device.loaded) device.load(normalize(reply["routerRtpCapabilities"]).obj())
            roomSub = signal.subscribe("call:${reply["roomId"]}") { m -> scope.launch { onRoomEvent(m) } }
            stateSub = signal.addStateListener { s -> scope.launch { onLine(s) } }
            wasConnected = true
            if (speaker) media.setSpeaker(true)
            // Someone may have started sending between the answer and the subscribe. Joining again is harmless and returns who is here now.
            val fresh = signal.request("media.join")
            reconcile(fresh["peers"].objs())
            set(CallState.JOINED)
            startResync()
            Triple(role!!, roomId!!, members)
        } catch (e: Throwable) {
            teardown()
            set(CallState.CLOSED)
            throw e
        }
    }

    /** Turns the microphone on and sends it. */
    suspend fun enableMicrophone(track: MediaTrack? = null): MediaTrack = withContext(dispatcher) {
        (track ?: media.microphone()).also { send(TrackSource.AUDIO, it) }
    }

    /** Turns the camera on and sends it. The front one, unless [frontCamera] is false. */
    suspend fun enableCamera(track: MediaTrack? = null): MediaTrack = withContext(dispatcher) {
        (track ?: media.camera(front)).also { send(TrackSource.VIDEO, it) }
    }

    /** Shares the phone's screen. Android needs a foreground service for screen capture: see the README. */
    suspend fun shareScreen(track: MediaTrack? = null): MediaTrack = withContext(dispatcher) {
        (track ?: media.screen()).also {
            it.onEnded = { scope.launch { stop(TrackSource.SCREEN) } }
            send(TrackSource.SCREEN, it)
        }
    }

    /** Mutes or unmutes what is being sent. Nothing is sent while muted, and the others are told. */
    suspend fun setMuted(source: TrackSource, muted: Boolean) = withContext(dispatcher) {
        val p = producers[source] ?: return@withContext
        if (muted) p.pause() else p.resume()
        signal.request(if (muted) "media.pauseProducer" else "media.resumeProducer", mapOf("producerId" to p.id))
        Unit
    }

    fun isMuted(source: TrackSource): Boolean = synchronized(producers) { producers[source]?.paused ?: false }

    /** Flips between the front and back camera while the camera is on, without interrupting what is being sent. */
    fun switchCamera() {
        val p = synchronized(producers) { producers[TrackSource.VIDEO] } ?: throw CallException("The camera is not on.")
        media.switchCamera(p.track)
        front = !front
    }

    /** Plays sound through the loudspeaker (true) or the earpiece (false). Remembered across a reconnect. */
    fun setSpeaker(on: Boolean) {
        speaker = on
        media.setSpeaker(on)
    }

    /** Stops sending a track and lets go of the device (the camera light goes off). */
    suspend fun stop(source: TrackSource) = withContext(dispatcher) {
        val p = synchronized(producers) { producers.remove(source) } ?: return@withContext
        p.close()
        runCatching { signal.request("media.closeProducer", mapOf("producerId" to p.id)) }
        Unit
    }

    /** What is arriving now, one entry per track: bytes, packets, losses and jitter. For a quality indicator, or to see that media is really flowing. */
    suspend fun stats(): List<CallStats> = withContext(dispatcher) {
        consumers.values.map { c ->
            var bytes = 0L; var packets = 0L; var lost = 0L; var jitter = 0.0
            for (r in c.consumer.getStats()) {
                if (r["type"] != "inbound-rtp") continue
                bytes += (r["bytesReceived"] as? Number)?.toLong() ?: 0L
                packets += (r["packetsReceived"] as? Number)?.toLong() ?: 0L
                lost += (r["packetsLost"] as? Number)?.toLong() ?: 0L
                jitter = maxOf(jitter, (r["jitter"] as? Number)?.toDouble() ?: 0.0)
            }
            CallStats(c.identity, c.source, bytes, packets, lost, jitter)
        }
    }

    /** Everything the engine reports about each received track, as it reports it. For diagnosing; [stats] is the summary. */
    suspend fun rawStats(): Map<String, List<Map<String, Any?>>> = withContext(dispatcher) {
        consumers.values.associate { "${it.identity}:${it.source.wire}" to it.consumer.getStats() }
    }

    /** Everything the engine reports about the connection that receives (or sends): candidate pairs, transport, streams. For diagnosing. */
    suspend fun connectionStats(receiving: Boolean = true): List<Map<String, Any?>> = withContext(dispatcher) {
        (if (receiving) recvT else sendT)?.getStats() ?: emptyList()
    }

    /** Leaves the room and lets go of the camera, the microphone and the connection. */
    suspend fun leave() = withContext(dispatcher) {
        if (state == CallState.CLOSED || state == CallState.IDLE) return@withContext
        runCatching { signal.request("media.leave") }
        teardown()
        set(CallState.CLOSED)
    }

    /** Leaves, and stops the call's thread. The call cannot be used again. */
    suspend fun dispose() {
        leave()
        scope.cancel()
        exec.shutdown()
    }

    // ───────────────────────────── transports

    private suspend fun transport(direction: String): Transport {
        (if (direction == "send") sendT else recvT)?.takeIf { !it.closed }?.let { return it }
        val info = signal.request("media.createTransport", mapOf("direction" to direction))
        val t = device.createTransport(
            direction,
            TransportOptions(
                id = info["id"] as String,
                iceParameters = normalize(info["iceParameters"]).obj(),
                iceCandidates = normalize(info["iceCandidates"]).arr(),
                dtlsParameters = normalize(info["dtlsParameters"]).obj(),
                iceServers = (joined?.get("iceServers") as? List<*>)?.map { normalize(it).obj() } ?: emptyList(),
                iceTransportPolicy = if (forceTcp) "relay" else "all",
            ),
        )
        t.onConnect = { dtls -> signal.request("media.connectTransport", mapOf("transportId" to t.id, "dtlsParameters" to dtls)); Unit }
        if (direction == "send") {
            t.onProduce = { kind, rtpParameters, appData ->
                val source = appData["source"] as? String ?: kind
                signal.request("media.produce", mapOf("transportId" to t.id, "kind" to source, "rtpParameters" to rtpParameters))["id"] as String
            }
        }
        t.onConnectionStateChange = { s -> scope.launch { onTransportState(t, s) } }
        if (direction == "send") sendT = t else recvT = t
        return t
    }

    /**
     * A path that failed is restarted with fresh ICE parameters from the server. One that is only `disconnected` (a phone moving from
     * Wi-Fi to mobile data) gets a few seconds to recover by itself first.
     */
    private fun onTransportState(t: Transport, s: String) {
        lapse.remove(t.id)?.cancel()
        when (s) {
            "failed" -> scope.launch { restartIce(t) }
            "disconnected" -> lapse[t.id] = scope.launch {
                delay(disconnectedGraceMs)
                lapse.remove(t.id)
                if (!t.closed) restartIce(t)
            }
        }
    }

    private suspend fun restartIce(t: Transport) {
        try {
            val r = signal.request("media.restartIce", mapOf("transportId" to t.id))
            t.restartIce(normalize(r["iceParameters"]).obj())
        } catch (e: Throwable) {
            error(e)
        }
    }

    // ───────────────────────────── sending

    private suspend fun send(source: TrackSource, track: MediaTrack) {
        if (state != CallState.JOINED) throw CallException("Join the call first.")
        if (role == CallRole.LISTENER) throw CallException("Your role in this call can only listen.", "forbidden")
        // Sending the same thing again replaces it: the old track is closed first.
        if (producers.containsKey(source)) stop(source)
        val t = transport("send")
        val producer = t.produce(
            track,
            appData = json("source" to source.wire),
            maxBitrate = when (source) { TrackSource.AUDIO -> 64_000; TrackSource.SCREEN -> 2_500_000; TrackSource.VIDEO -> 1_200_000 },
            codecOptions = if (source == TrackSource.AUDIO) json("opusDtx" to true, "opusFec" to true) else null,
        )
        track.onEnded = { scope.launch { stop(source) } }
        synchronized(producers) { producers[source] = producer }
    }

    // ───────────────────────────── receiving

    private suspend fun reconcile(list: List<Json>) {
        val here = list.map { it["identity"] as String }.toSet()
        for (p in list) {
            val id = p["identity"] as String
            if (!peers.containsKey(id)) {
                val peer = CallPeer(id, roleOf(p["role"]))
                synchronized(peers) { peers[id] = peer }
                emit { onPeerJoined?.invoke(peer) }
            }
        }
        for (id in peers.keys.toList()) {
            if (id !in here) {
                synchronized(peers) { peers.remove(id) }
                dropFrom(id)
                emit { onPeerLeft?.invoke(id) }
            }
        }
        val live = list.flatMap { it["producers"].objs().map { x -> x["id"] as String } }.toSet()
        for ((producerId, c) in consumers.entries.toList()) if (producerId !in live) endTrack(producerId, c.identity, c.source)
        for (p in list) for (pr in p["producers"].objs()) consume(pr["id"] as String, p["identity"] as String, sourceOf(pr["kind"]), pr["paused"] == true)
    }

    private suspend fun consume(producerId: String, identity: String, kind: TrackSource, paused: Boolean = false) {
        if (consumers.containsKey(producerId) || !fetching.add(producerId)) return
        try {
            val t = transport("recv")
            val r = signal.request("media.consume", mapOf("producerId" to producerId, "rtpCapabilities" to device.rtpCapabilities))
            val source = sourceOf(r["source"])
            val who = r["identity"] as String
            val consumer = t.consume(
                id = r["id"] as String,
                producerId = r["producerId"] as String,
                kind = r["kind"] as String,
                rtpParameters = normalize(r["rtpParameters"]).obj(),
                appData = json("source" to source.wire),
            )
            consumers[producerId] = Consumed(consumer, who, source)
            // The server made it paused so nothing is lost before the player is ready; it starts now.
            signal.request("media.resumeConsumer", mapOf("consumerId" to consumer.id))
            emit { onTrack?.invoke(RemoteTrack(who, source, consumer.track, producerId)) }
            if (paused) emit { onPaused?.invoke(TrackPaused(who, source, producerId, true)) }
            consumer.onTransportClose = { scope.launch { endTrack(producerId, who, source) } }
        } catch (e: Throwable) {
            error(if (e is CallException) e else CallException("Could not play $identity's ${kind.wire}: ${e.message}"))
        } finally {
            fetching.remove(producerId)
        }
    }

    private suspend fun endTrack(producerId: String, identity: String, source: TrackSource) {
        val c = consumers.remove(producerId) ?: return
        c.consumer.close()
        emit { onTrackEnded?.invoke(TrackEnded(identity, source, producerId)) }
    }

    private suspend fun dropFrom(identity: String) {
        for ((id, c) in consumers.entries.toList()) if (c.identity == identity) endTrack(id, c.identity, c.source)
    }

    private suspend fun onRoomEvent(m: RealtimeMessage) {
        val d = (m.data as? JSONObject)?.let { normalize(it).obj() } ?: json()
        val me = joined?.get("identity")
        when (m.event) {
            "peerJoined" -> {
                val id = d["identity"] as? String
                if (id != null && id != me && !peers.containsKey(id)) {
                    val peer = CallPeer(id, roleOf(d["role"]))
                    synchronized(peers) { peers[id] = peer }
                    emit { onPeerJoined?.invoke(peer) }
                }
            }
            "peerLeft" -> {
                val id = d["identity"] as? String
                if (id != null && synchronized(peers) { peers.remove(id) } != null) {
                    dropFrom(id)
                    emit { onPeerLeft?.invoke(id) }
                }
            }
            "newProducer" -> if (d["identity"] != me) consume(d["producerId"] as String, d["identity"] as String, sourceOf(d["kind"]))
            "producerClosed" -> consumers[d["producerId"]]?.let { endTrack(d["producerId"] as String, it.identity, it.source) }
            "producerPaused" -> consumers[d["producerId"]]?.let { c -> emit { onPaused?.invoke(TrackPaused(c.identity, c.source, d["producerId"] as String, d["paused"] == true)) } }
            "roomEnded", "roomEnding" -> {
                val reason = "${d["reason"] ?: "ended"}"
                teardown()
                set(CallState.CLOSED)
                emit { onEnded?.invoke(reason) }
            }
        }
    }

    // ───────────────────────────── the line coming and going

    /**
     * Room events travel over best-effort publish and subscribe, so a call does not rely on every one arriving: now and then it asks the
     * server who is in the room and what they are sending, and catches up on anything it missed.
     */
    private fun startResync() {
        if (resyncJob != null || resyncMs <= 0) return
        resyncJob = scope.launch {
            while (true) {
                delay(resyncMs)
                resync()
            }
        }
    }

    private suspend fun resync() {
        if (state != CallState.JOINED || resyncing) return
        resyncing = true
        try {
            val fresh = signal.request("media.join")
            if (state == CallState.JOINED) reconcile(fresh["peers"].objs())
        } catch (_: Throwable) {
            // A failed question is asked again next time; a line that is really gone is handled by onLine.
        } finally {
            resyncing = false
        }
    }

    /** The socket dropped and came back: take the place in the room again and see what changed while away. */
    private suspend fun onLine(s: RealtimeState) {
        if (s == RealtimeState.RECONNECTING && state == CallState.JOINED) set(CallState.RECONNECTING)
        if (s == RealtimeState.CLOSED && state != CallState.CLOSED && state != CallState.IDLE) {
            teardown()
            set(CallState.CLOSED)
            emit { onEnded?.invoke("disconnected") }
            return
        }
        if (s != RealtimeState.CONNECTED || !wasConnected || state != CallState.RECONNECTING || resyncing) return
        resyncing = true
        try {
            val fresh = signal.request("media.join")
            set(CallState.JOINED)
            for (t in listOf(sendT, recvT)) if (t != null && !t.closed) restartIce(t)
            reconcile(fresh["peers"].objs())
        } catch (e: Throwable) {
            // The server no longer has this person (they were away too long): the call is over for them.
            teardown()
            set(CallState.CLOSED)
            emit { onEnded?.invoke((e as? CallException)?.reason ?: "rejoin_failed") }
        } finally {
            resyncing = false
        }
    }

    private suspend fun teardown() {
        resyncJob?.cancel(); resyncJob = null
        stateSub?.cancel(); roomSub?.cancel()
        stateSub = null; roomSub = null
        for (j in lapse.values) j.cancel()
        lapse.clear()
        for (p in synchronized(producers) { producers.values.toList().also { producers.clear() } }) p.close()
        for (c in consumers.values.toList()) c.consumer.close()
        consumers.clear()
        sendT?.close(); recvT?.close()
        sendT = null; recvT = null
        synchronized(peers) { peers.clear() }
        signal.disconnect()
    }
}
