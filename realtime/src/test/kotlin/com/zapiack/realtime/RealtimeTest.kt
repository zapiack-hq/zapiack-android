package com.zapiack.realtime

import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** A line that tests drive: they push frames in as the server would, and read what the client sent. */
class FakeSocket(val url: String, private val listener: RealtimeSocketListener) : RealtimeSocket {
    val sent = CopyOnWriteArrayList<JSONObject>()
    @Volatile var closed = false
    override fun send(text: String) { sent += JSONObject(text) }
    override fun close() { closed = true }

    /** What the server does. */
    fun receive(vararg pairs: Pair<String, Any?>) = listener.onMessage(JSONObject(mapOf(*pairs)).toString())
    fun receiveJson(json: String) = listener.onMessage(json)
    fun drop(code: Int = 1006) = listener.onClosed(code)
    fun ops(op: String) = sent.filter { it.optString("op") == op }
}

class Rig(tokens: List<String> = listOf("tok-1", "tok-2", "tok-3", "tok-4")) {
    val sockets = CopyOnWriteArrayList<FakeSocket>()
    private val queue = java.util.concurrent.ConcurrentLinkedQueue(tokens)
    @Volatile var asked = 0

    fun client(heartbeatMs: Long = 25_000, ackTimeoutMs: Long = 5_000) = ZapiackRealtime(
        tokens = { asked++; queue.poll() ?: throw java.io.IOException("server down") },
        url = "wss://rt.test/",
        heartbeatMs = heartbeatMs,
        backoffMinMs = 20,
        backoffMaxMs = 50,
        ackTimeoutMs = ackTimeoutMs,
        sockets = { url, l -> FakeSocket(url, l).also { sockets += it } },
    )

    val last get() = sockets.last()

    fun until(what: String, timeoutMs: Long = 3000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for: $what")
            Thread.sleep(5)
        }
    }

    /** Connects, and has the server say hello. */
    fun up(rt: ZapiackRealtime, id: String = "cn_1") {
        val done = CountDownLatch(1)
        rt.connect { done.countDown() }
        until("a socket") { sockets.isNotEmpty() }
        last.receive("op" to "hello", "connId" to id)
        assertTrue(done.await(3, TimeUnit.SECONDS), "connect never completed")
    }
}

class RealtimeTest {
    @Test fun `connects with the token in the address and reports state`() {
        val rig = Rig(listOf("a b/c"))
        val rt = rig.client()
        val states = Collections.synchronizedList(mutableListOf<RealtimeState>())
        rt.onState = { states += it }
        rig.up(rt, "cn_9")
        assertEquals("wss://rt.test/?token=a%20b%2Fc", rig.last.url)
        assertEquals(RealtimeState.CONNECTED, rt.state)
        assertEquals("cn_9", rt.connectionId)
        assertEquals(listOf(RealtimeState.CONNECTING, RealtimeState.CONNECTED), states.toList())
    }

    @Test fun `subscribes when the line opens and delivers messages`() {
        val rig = Rig()
        val rt = rig.client()
        val got = CopyOnWriteArrayList<String>()
        // Subscribed before connecting: sent as soon as it can be.
        rt.subscribe("chat:r1") { got += "${it.event}:${it.data}" }
        rig.up(rt)
        rig.until("subscribe") { rig.last.ops("subscribe").any { it.getString("channel") == "chat:r1" } }
        rig.last.receive("op" to "message", "msgId" to "1-0", "ts" to 1, "channel" to "chat:r1", "event" to "message", "data" to mapOf("hi" to 1), "from" to "u1")
        rig.last.receive("op" to "message", "msgId" to "1-1", "ts" to 1, "channel" to "other", "event" to "message", "data" to 0, "from" to "u1")
        rig.until("the message") { got.size == 1 }
        assertEquals(listOf("message:{\"hi\":1}"), got.toList())
    }

    @Test fun `publish resolves on ack and rejects on refusal`() {
        val rig = Rig()
        val rt = rig.client()
        rig.up(rt)

        val ok = CopyOnWriteArrayList<Result<PublishResult>>()
        rt.publish("c", mapOf("a" to 1), persist = true) { ok += it }
        rig.until("the publish") { rig.last.ops("publish").isNotEmpty() }
        var frame = rig.last.ops("publish").last()
        assertTrue(frame.getBoolean("persist"))
        rig.last.receive("op" to "ack", "ackId" to frame.getString("ackId"), "msgId" to "9-0", "delivered" to 3)
        rig.until("the result") { ok.size == 1 }
        assertEquals(PublishResult("9-0", 3), ok[0].getOrThrow())

        val refused = CopyOnWriteArrayList<Result<PublishResult>>()
        rt.publish("c", 1) { refused += it }
        rig.until("the second publish") { rig.last.ops("publish").size == 2 }
        frame = rig.last.ops("publish").last()
        rig.last.receive("op" to "error", "code" to 403, "message" to "Not allowed to publish to c.", "ref" to frame.getString("ackId"))
        rig.until("the refusal") { refused.size == 1 }
        assertEquals(403, (refused[0].exceptionOrNull() as RealtimeException).code)
    }

    @Test fun `publish gives up when nothing confirms`() {
        val rig = Rig()
        val rt = rig.client(ackTimeoutMs = 100)
        rig.up(rt)
        val out = CopyOnWriteArrayList<Result<PublishResult>>()
        rt.publish("c", null) { out += it }
        rig.until("the timeout") { out.size == 1 }
        assertTrue(out[0].exceptionOrNull()!!.message!!.contains("did not confirm"))
    }

    @Test fun `without a callback a publish does not wait for or carry an ack id`() {
        val rig = Rig()
        val rt = rig.client()
        rig.up(rt)
        rt.publish("c", 1)
        rig.until("the publish") { rig.last.ops("publish").isNotEmpty() }
        assertFalse(rig.last.ops("publish").last().has("ackId"))
    }

    @Test fun `reconnects with a new token and resumes from the last persisted message`() {
        val rig = Rig()
        val rt = rig.client()
        val got = CopyOnWriteArrayList<String>()
        rt.subscribe("c") { got += "${it.data}:${it.replayed}" }
        rig.up(rt)
        rig.last.receive("op" to "message", "msgId" to "10-0", "ts" to 1, "channel" to "c", "event" to "message", "data" to "a", "persisted" to true)
        rig.last.receive("op" to "message", "msgId" to "11-0", "ts" to 1, "channel" to "c", "event" to "message", "data" to "b", "persisted" to true)
        rig.until("two messages") { got.size == 2 }

        rig.last.drop()
        rig.until("a second socket") { rig.sockets.size == 2 }
        assertTrue(rig.sockets[1].url.contains("token=tok-2"))
        rig.sockets[1].receive("op" to "hello", "connId" to "cn_2")
        rig.until("the resume") { rig.sockets[1].ops("subscribe").any { it.optString("resumeFrom") == "11-0" } }
        // The server replays one already seen and one new.
        rig.sockets[1].receive("op" to "message", "msgId" to "11-0", "ts" to 1, "channel" to "c", "event" to "message", "data" to "b", "persisted" to true, "replayed" to true)
        rig.sockets[1].receive("op" to "message", "msgId" to "12-0", "ts" to 1, "channel" to "c", "event" to "message", "data" to "c", "persisted" to true, "replayed" to true)
        rig.until("the new one") { got.size == 3 }
        assertEquals(listOf("a:false", "b:false", "c:true"), got.toList())
    }

    @Test fun `stops for good when the project refuses it`() {
        val rig = Rig()
        val rt = rig.client()
        val out = CopyOnWriteArrayList<Result<Unit>>()
        rt.connect { out += it }
        rig.until("a socket") { rig.sockets.isNotEmpty() }
        rig.last.drop(4403)
        rig.until("the refusal") { out.size == 1 }
        assertEquals(403, (out[0].exceptionOrNull() as RealtimeException).code)
        assertEquals(RealtimeState.CLOSED, rt.state)
        Thread.sleep(150)
        assertEquals(1, rig.sockets.size)
    }

    @Test fun `an error the server marks fatal also stops it, for runtimes that cannot see the close code`() {
        val rig = Rig()
        val rt = rig.client()
        val out = CopyOnWriteArrayList<Result<Unit>>()
        rt.connect { out += it }
        rig.until("a socket") { rig.sockets.isNotEmpty() }
        rig.last.receive("op" to "error", "code" to 403, "message" to "no", "fatal" to true)
        Thread.sleep(30)
        rig.last.drop(1006)
        rig.until("the refusal") { out.size == 1 }
        assertEquals(RealtimeState.CLOSED, rt.state)
    }

    @Test fun `replaces a refused token at once rather than waiting`() {
        val rig = Rig()
        val rt = rig.client()
        rig.up(rt)
        rig.last.drop(4401)
        rig.until("a second socket", 1000) { rig.sockets.size == 2 }
        assertTrue(rig.sockets[1].url.contains("tok-2"))
    }

    @Test fun `holds sends while reconnecting and sends them in order`() {
        val rig = Rig()
        val rt = rig.client()
        rig.up(rt)
        val states = CopyOnWriteArrayList<RealtimeState>()
        rt.onState = { states += it }
        rig.last.drop()
        // Only once the client has noticed the line is gone: a write in the instant before would go to a dead socket.
        rig.until("the client to notice") { RealtimeState.RECONNECTING in states }
        rt.publish("c", "one")
        rt.publish("c", "two")
        rig.until("a second socket") { rig.sockets.size == 2 }
        rig.sockets[1].receive("op" to "hello", "connId" to "cn_2")
        rig.until("both sends") { rig.sockets[1].ops("publish").size == 2 }
        assertEquals(listOf("one", "two"), rig.sockets[1].ops("publish").map { it.getString("data") })
    }

    @Test fun `disconnect rejects what was waiting`() {
        val rig = Rig()
        val rt = rig.client()
        rig.up(rt)
        val out = CopyOnWriteArrayList<Result<PublishResult>>()
        rt.publish("c", null) { out += it }
        rig.until("the publish") { rig.last.ops("publish").isNotEmpty() }
        rt.disconnect()
        rig.until("the rejection") { out.size == 1 }
        assertEquals("Disconnected.", out[0].exceptionOrNull()!!.message)
        assertEquals(RealtimeState.CLOSED, rt.state)
    }

    @Test fun `keeps presence and signals one connection`() {
        val rig = Rig()
        val rt = rig.client()
        rig.up(rt)
        rt.subscribe("c") { }
        val events = CopyOnWriteArrayList<String>()
        val presence = rt.presence("c")
        presence.onChange { action, m -> events += "$action:${m.user ?: "-"}" }
        rig.until("subscribed") { rig.last.ops("subscribe").isNotEmpty() }
        rig.last.receiveJson("""{"op":"presence.members","channel":"c","members":[{"connId":"cn_a","user":"ann","data":null,"at":1}]}""")
        rig.last.receiveJson("""{"op":"message","msgId":"2-0","ts":2,"channel":"c","event":"presence.enter","data":{"connId":"cn_b","user":"bob","data":1},"from":"cn_b"}""")
        rig.until("bob") { events.size == 1 }
        assertEquals(listOf("ann", "bob"), presence.members().map { it.user })
        rig.last.receiveJson("""{"op":"message","msgId":"3-0","ts":3,"channel":"c","event":"presence.leave","data":{"connId":"cn_a","user":"ann","data":null},"from":"cn_a"}""")
        rig.until("ann leaves") { events.size == 2 }
        assertEquals(listOf("bob"), presence.members().map { it.user })
        assertEquals(listOf("enter:bob", "leave:ann"), events.toList())

        rt.signal("c", "cn_x", "offer", mapOf("sdp" to "v=0"))
        rig.until("the signal") { rig.last.ops("signal").isNotEmpty() }
        assertEquals("cn_x", rig.last.ops("signal").last().getString("to"))
    }

    @Test fun `gives up on a silent line and pings on a beat`() {
        val rig = Rig()
        val rt = rig.client(heartbeatMs = 50)
        rig.up(rt)
        // Nothing is heard for more than two beats: the line is closed and a new one opened.
        rig.until("a second socket", 3000) { rig.sockets.size == 2 }
        assertTrue(rig.sockets[0].closed)
        assertTrue(rig.sockets[0].ops("ping").isNotEmpty())
    }

    @Test fun `message ids are ordered by time then sequence`() {
        assertTrue(isAfter("11-0", "10-5"))
        assertTrue(isAfter("10-2", "10-1"))
        assertFalse(isAfter("10-1", "10-1"))
        assertFalse(isAfter("9-9", "10-0"))
    }
}
