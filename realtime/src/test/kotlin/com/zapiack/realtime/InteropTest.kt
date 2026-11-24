package com.zapiack.realtime

import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

/**
 * The client against a real gateway, over a real OkHttp WebSocket. Runs only when RT_INTEROP names the gateway
 * (for example `RT_INTEROP=localhost:3015,localhost:3016` = gateway, then the address of the server that mints tokens).
 */
class InteropTest {
    private val spec = System.getenv("RT_INTEROP")?.split(",")

    private fun client(query: String) = ZapiackRealtime(
        tokens = { URL("http://${spec!![1]}/?$query").readText() },
        url = "ws://${spec!![0]}",
        backoffMinMs = 50,
        backoffMaxMs = 200,
    )

    private fun until(what: String, ms: Long = 4000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) fail("Timed out waiting for: $what")
            Thread.sleep(10)
        }
    }

    private fun connect(rt: ZapiackRealtime): Result<Unit> {
        val out = CopyOnWriteArrayList<Result<Unit>>()
        rt.connect { out += it }
        until("connect") { out.isNotEmpty() }
        return out[0]
    }

    private fun publish(rt: ZapiackRealtime, ch: String, data: Any?, persist: Boolean = false): Result<PublishResult> {
        val out = CopyOnWriteArrayList<Result<PublishResult>>()
        rt.publish(ch, data, persist = persist) { out += it }
        until("ack") { out.isNotEmpty() }
        return out[0]
    }

    @Test fun `talks to the real gateway`() {
        if (spec == null) return
        val ch = "chat:interop-kt-${System.nanoTime()}"
        val a = client("user=alice")
        val b = client("user=bob")
        val bGot = CopyOnWriteArrayList<RealtimeMessage>()

        assertTrue(connect(a).isSuccess)
        assertTrue(a.connectionId!!.startsWith("cn_"))
        a.subscribe(ch) { }
        assertTrue(connect(b).isSuccess)
        val stopB = b.subscribe(ch) { bGot += it }
        Thread.sleep(150)

        val r = publish(a, ch, mapOf("text" to "hello", "n" to 1), persist = true).getOrThrow()
        assertEquals(2, r.delivered)
        until("bob receives") { bGot.size == 1 }
        assertEquals("alice", bGot[0].from)
        assertEquals("message", bGot[0].event)

        // Resume: bob goes away, alice publishes twice, bob returns asking from the last id he saw.
        val lastSeen = bGot[0].msgId
        stopB.cancel()
        b.disconnect()
        publish(a, ch, mapOf("n" to 2), persist = true).getOrThrow()
        publish(a, ch, mapOf("n" to 3), persist = true).getOrThrow()
        val b2 = client("user=bob")
        val got2 = CopyOnWriteArrayList<RealtimeMessage>()
        assertTrue(connect(b2).isSuccess)
        b2.subscribe(ch, resumeFrom = lastSeen) { got2 += it }
        until("replay") { got2.size == 2 }
        assertEquals(listOf(2, 3), got2.map { (it.data as org.json.JSONObject).getInt("n") })
        assertTrue(got2.all { it.replayed })

        // Presence and signal.
        val events = CopyOnWriteArrayList<String>()
        b2.presence(ch).onChange { action, m -> events += "$action:${m.user}" }
        a.presence(ch).enter(mapOf("name" to "Alice"))
        until("presence") { events.isNotEmpty() }
        assertEquals("enter:alice", events[0])
        val sig = CopyOnWriteArrayList<RealtimeMessage>()
        b2.subscribe(ch) { if (it.event == "offer") sig += it }
        Thread.sleep(100)
        a.signal(ch, b2.connectionId!!, "offer", mapOf("sdp" to "v=0"))
        until("signal") { sig.size == 1 }

        // Refusals.
        assertEquals(403, (publish(a, "admin:x", 1).exceptionOrNull() as RealtimeException).code)
        val off = client("off=1")
        val refused = connect(off)
        assertEquals(403, (refused.exceptionOrNull() as RealtimeException).code)
        assertEquals(RealtimeState.CLOSED, off.state)

        a.disconnect(); b2.disconnect()
    }
}
