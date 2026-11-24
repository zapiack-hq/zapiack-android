package com.zapiack.realtime

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Asking a service something over the line: the way a call client talks to the media server. */
class RequestTest {
    private fun answers() = CopyOnWriteArrayList<Result<JSONObject>>()

    @Test fun `sends an rpc frame and completes with the answer`() {
        val rig = Rig(); val rt = rig.client(); rig.up(rt)
        val got = answers()
        rt.request("media.join", mapOf("a" to 1)) { got += it }
        rig.until("the frame") { rig.last.ops("rpc").isNotEmpty() }
        val frame = rig.last.ops("rpc").single()
        assertEquals("media.join", frame.getString("method"))
        assertEquals(1, frame.getJSONObject("data").getInt("a"))
        rig.last.receive("op" to "rpc.result", "correlationId" to frame.getString("correlationId"), "data" to mapOf("roomId" to "r1"))
        rig.until("the answer") { got.isNotEmpty() }
        assertEquals("r1", got.single().getOrThrow().getString("roomId"))
        rt.disconnect()
    }

    @Test fun `an answer with no data is an empty object`() {
        val rig = Rig(); val rt = rig.client(); rig.up(rt)
        val got = answers()
        rt.request("media.leave") { got += it }
        rig.until("the frame") { rig.last.ops("rpc").isNotEmpty() }
        rig.last.receive("op" to "rpc.result", "correlationId" to rig.last.ops("rpc").single().getString("correlationId"))
        rig.until("the answer") { got.isNotEmpty() }
        assertEquals(0, got.single().getOrThrow().length())
        rt.disconnect()
    }

    @Test fun `a refusal fails with the service's word for it`() {
        val rig = Rig(); val rt = rig.client(); rig.up(rt)
        val got = answers()
        rt.request("media.produce") { got += it }
        rig.until("the frame") { rig.last.ops("rpc").isNotEmpty() }
        rig.last.receive("op" to "rpc.error", "correlationId" to rig.last.ops("rpc").single().getString("correlationId"), "code" to "forbidden", "message" to "You can only listen.")
        rig.until("the answer") { got.isNotEmpty() }
        val e = got.single().exceptionOrNull() as RealtimeException
        assertEquals("forbidden", e.reason)
        assertEquals("You can only listen.", e.message)
        rt.disconnect()
    }

    @Test fun `is never queued, so with no line it fails at once`() {
        val rt = Rig().client()
        val got = answers()
        rt.request("media.join") { got += it }
        Rig().until("the answer") { got.isNotEmpty() }
        assertEquals("no_route", (got.single().exceptionOrNull() as RealtimeException).reason)
    }

    @Test fun `two answers that cross are matched to the right asker`() {
        val rig = Rig(); val rt = rig.client(); rig.up(rt)
        val a = answers(); val b = answers()
        rt.request("one") { a += it }
        rt.request("two") { b += it }
        rig.until("both frames") { rig.last.ops("rpc").size == 2 }
        val f = rig.last.ops("rpc")
        rig.last.receive("op" to "rpc.result", "correlationId" to f[1].getString("correlationId"), "data" to mapOf("n" to 2))
        rig.last.receive("op" to "rpc.result", "correlationId" to f[0].getString("correlationId"), "data" to mapOf("n" to 1))
        rig.until("both answers") { a.isNotEmpty() && b.isNotEmpty() }
        assertEquals(1, a.single().getOrThrow().getInt("n"))
        assertEquals(2, b.single().getOrThrow().getInt("n"))
        rt.disconnect()
    }

    @Test fun `the line dropping fails what is waiting`() {
        val rig = Rig(); val rt = rig.client(); rig.up(rt)
        val got = answers()
        rt.request("media.join") { got += it }
        rig.until("the frame") { rig.last.ops("rpc").isNotEmpty() }
        rig.last.drop()
        rig.until("the failure") { got.isNotEmpty() }
        assertEquals("no_route", (got.single().exceptionOrNull() as RealtimeException).reason)
        rt.disconnect()
    }

    @Test fun `gives up when the server never answers`() {
        val rig = Rig(); val rt = rig.client(ackTimeoutMs = 50); rig.up(rt)
        val got = answers()
        rt.request("media.join") { got += it }
        rig.until("the timeout", timeoutMs = 4000) { got.isNotEmpty() }
        assertEquals("timeout", (got.single().exceptionOrNull() as RealtimeException).reason)
        rt.disconnect()
    }

    @Test fun `a state listener hears what the app's own callback hears, and can be removed`() {
        val rig = Rig(); val rt = rig.client()
        val mine = CopyOnWriteArrayList<RealtimeState>()
        val sub = rt.addStateListener { mine += it }
        rig.up(rt)
        rig.until("connected") { mine.contains(RealtimeState.CONNECTED) }
        sub.cancel()
        rt.disconnect()
        Thread.sleep(100)
        assertTrue(RealtimeState.CLOSED !in mine)
        assertNull(null)
    }
}
