package com.zapiack.calls

import com.google.gson.Gson
import com.zapiack.realtime.RealtimeMessage
import com.zapiack.realtime.RealtimeState
import com.zapiack.realtime.RealtimeSubscription
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/*
 * The call itself: joining, sending, receiving, muting, the room changing, the network changing. The negotiation underneath is the real
 * one, fed the real SDP and router data from the golden file; only the network and the engine are faked.
 */

private val g: Json = normalize(Gson().fromJson(ZapiackCallTest::class.java.getResource("/mediasoup_golden.json")!!.readText(), LinkedHashMap::class.java)).obj()

private class FakeSignal : CallSignal {
    val asked = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
    val answers = HashMap<String, (Map<String, Any?>) -> Map<String, Any?>>()
    @Volatile var roomHandler: ((RealtimeMessage) -> Unit)? = null
    @Volatile var stateHandler: ((RealtimeState) -> Unit)? = null
    @Volatile var disconnected = false
    val subscribed = CopyOnWriteArrayList<String>()

    override suspend fun connect() {}
    override fun disconnect() { disconnected = true }
    override suspend fun request(method: String, data: Map<String, Any?>): Map<String, Any?> {
        asked += method to data
        return answers[method]?.invoke(data) ?: emptyMap()
    }

    override fun subscribe(channel: String, handler: (RealtimeMessage) -> Unit): RealtimeSubscription {
        subscribed += channel
        roomHandler = handler
        return RealtimeSubscription { }
    }

    override fun addStateListener(fn: (RealtimeState) -> Unit): RealtimeSubscription {
        stateHandler = fn
        return RealtimeSubscription { }
    }

    fun room(event: String, data: Map<String, Any?>) = roomHandler!!(RealtimeMessage("m", 0.0, "call:room-1", event, JSONObject(data), null, false))
    fun line(s: RealtimeState) = stateHandler!!(s)
    val methods: List<String> get() = asked.map { it.first }
}

private class FakeMedia : CallMedia {
    val tracks = CopyOnWriteArrayList<FakeTrack>()
    val calls = CopyOnWriteArrayList<String>()
    override suspend fun microphone(): MediaTrack = FakeTrack("audio").also { tracks += it }
    override suspend fun camera(front: Boolean): MediaTrack { calls += "camera:${if (front) "front" else "back"}"; return FakeTrack("video").also { tracks += it } }
    override suspend fun screen(): MediaTrack = FakeTrack("video", "track-screen").also { tracks += it }
    override fun setSpeaker(on: Boolean) { calls += "speaker:$on" }
    override fun switchCamera(track: MediaTrack) { calls += "switch:${track.id}" }
}

private class Rig(join: Map<String, Any?> = emptyMap(), graceMs: Long = 20, resyncMs: Long = 0) {
    val signal = FakeSignal()
    val media = FakeMedia()
    val factory: FakeFactory
    val call: ZapiackCall

    init {
        val recv = g["recv"].obj()
        val send = g["send"].obj()
        var n = 0
        signal.answers["media.join"] = { mapOf("roomId" to "room-1", "identity" to "me", "routerRtpCapabilities" to g["routerRtpCapabilities"], "iceServers" to emptyList<Any>(), "role" to "speaker", "peers" to emptyList<Any>()) + join }
        signal.answers["media.createTransport"] = { d ->
            val p = (if (d["direction"] == "send") send else recv)["transportParams"].obj()
            mapOf("id" to "t${++n}") + p
        }
        signal.answers["media.produce"] = { mapOf("id" to "p${++n}") }
        val consumers = recv["consumers"].objs()
        signal.answers["media.consume"] = { d ->
            val audio = d["producerId"].toString().contains("a")
            val c = consumers[if (audio) 0 else 1]
            mapOf("id" to c["id"], "producerId" to d["producerId"], "kind" to c["kind"], "source" to (if (audio) "audio" else "video"), "identity" to "ada", "rtpParameters" to c["rtpParameters"])
        }
        signal.answers["media.restartIce"] = { mapOf("iceParameters" to g["restart"].obj()["iceParameters"]) }
        factory = FakeFactory(
            native = g["sdps"].obj()["native"] as String,
            answers = listOf(recv["answerSdp"] as String),
            offerBySequence = listOf("a", "v", "av", "va", "avv", "vv", "ava").associateWith { g["sdps"].obj()[it] as String },
        )
        call = ZapiackCall(signal, factory, media, disconnectedGraceMs = graceMs, resyncMs = resyncMs)
    }
}

private fun ada(): Map<String, Any?> = mapOf("identity" to "ada", "role" to "host", "producers" to listOf(mapOf("id" to "p-a", "kind" to "audio", "paused" to false), mapOf("id" to "p-v", "kind" to "video", "paused" to false)))

private suspend fun until(what: String, ms: Long = 5_000, ok: () -> Boolean) {
    withTimeout(ms) { while (!ok()) delay(10) }
}

class ZapiackCallTest {
    @Test fun `joins, loads the device, listens to the room, and joins again to see anything that changed in between`() = runBlocking {
        val r = Rig()
        val (role, roomId, members) = r.call.join()
        assertEquals(CallRole.SPEAKER, role)
        assertEquals("room-1", roomId)
        assertTrue(members.isEmpty())
        assertEquals(listOf("media.join", "media.join"), r.signal.methods)
        assertEquals(listOf("call:room-1"), r.signal.subscribed)
        assertEquals(CallState.JOINED, r.call.state)
    }

    @Test fun `receives what others are already sending, and tells the app`() = runBlocking {
        val r = Rig(join = mapOf("peers" to listOf(ada())))
        val tracks = CopyOnWriteArrayList<String>()
        val peers = CopyOnWriteArrayList<String>()
        r.call.onTrack = { tracks += "${it.identity}:${it.source.wire}" }
        r.call.onPeerJoined = { peers += it.identity }
        r.call.join()
        until("two tracks") { tracks.size == 2 }
        assertEquals(listOf("ada"), peers.toList())
        assertEquals(listOf("ada:audio", "ada:video"), tracks.sorted())
        assertEquals(1, r.signal.methods.count { it == "media.createTransport" })
        assertEquals(g["recv"].obj()["recvRtpCapabilities"], r.signal.asked.first { it.first == "media.consume" }.second["rtpCapabilities"])
        assertContains(r.signal.methods, "media.resumeConsumer")
    }

    @Test fun `closes everything and reports the problem when the join is refused`() = runBlocking {
        val r = Rig()
        r.signal.answers["media.join"] = { throw CallException("This token is not for a call.", "forbidden") }
        val e = assertFailsWith<CallException> { r.call.join() }
        assertEquals("forbidden", e.reason)
        assertEquals(CallState.CLOSED, r.call.state)
        assertTrue(r.signal.disconnected)
    }

    @Test fun `the microphone makes a send transport, connects it once, and tells the server the audio with the engine's parameters`() = runBlocking {
        val r = Rig()
        r.call.join()
        r.call.enableMicrophone()
        assertEquals(listOf(TrackSource.AUDIO), r.call.sending)
        val produce = r.signal.asked.first { it.first == "media.produce" }.second
        assertEquals("audio", produce["kind"])
        val params = produce["rtpParameters"].obj()
        assertEquals("0", params["mid"])
        assertEquals("audio/opus", params["codecs"].objs().first()["mimeType"])
        assertEquals(1, r.signal.methods.count { it == "media.connectTransport" })
    }

    @Test fun `a camera after the microphone shares the transport and its connection`() = runBlocking {
        val r = Rig()
        r.call.join()
        r.call.enableMicrophone()
        r.call.enableCamera()
        assertEquals(setOf(TrackSource.AUDIO, TrackSource.VIDEO), r.call.sending.toSet())
        assertEquals(1, r.signal.methods.count { it == "media.createTransport" })
        assertEquals(1, r.signal.methods.count { it == "media.connectTransport" })
        assertEquals(listOf("audio", "video"), r.signal.asked.filter { it.first == "media.produce" }.map { it.second["kind"] })
    }

    @Test fun `a listener may receive but never send`() = runBlocking {
        val r = Rig(join = mapOf("role" to "listener"))
        r.call.join()
        val e = assertFailsWith<CallException> { r.call.enableMicrophone() }
        assertEquals("forbidden", e.reason)
        assertTrue(r.call.sending.isEmpty())
    }

    @Test fun `sending before joining is refused`() = runBlocking {
        val r = Rig()
        assertFailsWith<CallException> { r.call.enableMicrophone() }
        Unit
    }

    @Test fun `muting sends nothing and tells the server, and unmuting reverses it`() = runBlocking {
        val r = Rig()
        r.call.join()
        val t = r.call.enableMicrophone()
        r.call.setMuted(TrackSource.AUDIO, true)
        assertFalse(t.enabled)
        assertTrue(r.call.isMuted(TrackSource.AUDIO))
        assertContains(r.signal.methods, "media.pauseProducer")
        r.call.setMuted(TrackSource.AUDIO, false)
        assertTrue(t.enabled)
        assertContains(r.signal.methods, "media.resumeProducer")
    }

    @Test fun `stopping a track closes it on the server and lets go of the device`() = runBlocking {
        val r = Rig()
        r.call.join()
        val t = r.call.enableCamera() as FakeTrack
        r.call.stop(TrackSource.VIDEO)
        assertTrue(t.stopped)
        assertTrue(r.call.sending.isEmpty())
        assertContains(r.signal.methods, "media.closeProducer")
    }

    @Test fun `a refused produce leaves nothing half-sent`() = runBlocking {
        val r = Rig()
        r.call.join()
        r.signal.answers["media.produce"] = { throw CallException("no", "forbidden") }
        assertFailsWith<CallException> { r.call.enableMicrophone() }
        assertTrue(r.call.sending.isEmpty())
    }

    @Test fun `a person arriving, then their track, then leaving, and everything they brought goes with them`() = runBlocking {
        val r = Rig()
        val log = CopyOnWriteArrayList<String>()
        r.call.onPeerJoined = { log += "+${it.identity}" }
        r.call.onTrack = { log += "track:${it.source.wire}" }
        r.call.onTrackEnded = { log += "ended:${it.source.wire}" }
        r.call.onPeerLeft = { log += "-$it" }
        r.call.join()
        r.signal.room("peerJoined", mapOf("identity" to "ada", "role" to "host"))
        r.signal.room("newProducer", mapOf("identity" to "ada", "producerId" to "p-a", "kind" to "audio"))
        until("the track") { log.contains("track:audio") }
        r.signal.room("peerLeft", mapOf("identity" to "ada"))
        until("the leaving") { log.contains("-ada") }
        assertEquals(listOf("+ada", "track:audio", "ended:audio", "-ada"), log.toList())
    }

    @Test fun `events about this person are ignored`() = runBlocking {
        val r = Rig()
        val log = CopyOnWriteArrayList<String>()
        r.call.onPeerJoined = { log += it.identity }
        r.call.join()
        r.signal.room("peerJoined", mapOf("identity" to "me", "role" to "speaker"))
        r.signal.room("newProducer", mapOf("identity" to "me", "producerId" to "p-a", "kind" to "audio"))
        delay(100)
        assertTrue(log.isEmpty())
        assertFalse(r.signal.methods.contains("media.consume"))
    }

    @Test fun `someone else muting is passed on`() = runBlocking {
        val r = Rig(join = mapOf("peers" to listOf(ada())))
        val log = CopyOnWriteArrayList<String>()
        r.call.onPaused = { log += "${it.source.wire}:${it.paused}" }
        r.call.join()
        until("the tracks") { runBlocking { r.call.stats().size == 2 } }
        r.signal.room("producerPaused", mapOf("producerId" to "p-a", "paused" to true))
        until("the event") { log.isNotEmpty() }
        assertEquals(listOf("audio:true"), log.toList())
    }

    @Test fun `two events for the same track make one consumer`() = runBlocking {
        val r = Rig()
        r.call.join()
        r.signal.room("newProducer", mapOf("identity" to "ada", "producerId" to "p-a", "kind" to "audio"))
        r.signal.room("newProducer", mapOf("identity" to "ada", "producerId" to "p-a", "kind" to "audio"))
        delay(200)
        assertEquals(1, r.signal.methods.count { it == "media.consume" })
    }

    @Test fun `the room being ended closes the call and says so`() = runBlocking {
        val r = Rig()
        val ended = CopyOnWriteArrayList<String>()
        r.call.onEnded = { ended += it }
        r.call.join()
        r.call.enableMicrophone()
        r.signal.room("roomEnded", mapOf("reason" to "ended_by_host"))
        until("the end") { ended.isNotEmpty() }
        assertEquals(listOf("ended_by_host"), ended.toList())
        assertEquals(CallState.CLOSED, r.call.state)
        assertTrue(r.signal.disconnected)
        assertTrue(r.media.tracks.first().stopped)
    }

    @Test fun `opens the front camera, and the back one when asked, and switching flips the live camera and the next one opened`() = runBlocking {
        val r = Rig()
        r.call.join()
        r.call.enableCamera()
        assertEquals(listOf("camera:front"), r.media.calls.toList())
        r.call.switchCamera()
        assertEquals("switch:track-video", r.media.calls.last())
        r.call.stop(TrackSource.VIDEO)
        r.call.enableCamera()
        assertEquals("camera:back", r.media.calls.last())
    }

    @Test fun `switching with no camera on is an error, and the speaker choice is remembered for a call joined later`() = runBlocking {
        val r = Rig()
        r.call.setSpeaker(true)
        r.call.join()
        assertEquals(listOf("speaker:true", "speaker:true"), r.media.calls.toList())
        assertFailsWith<CallException> { r.call.switchCamera() }
        Unit
    }

    @Test fun `a path that stays disconnected is restarted with fresh parameters, and one that comes back by itself is left alone`() = runBlocking {
        val r = Rig()
        r.call.join()
        r.call.enableMicrophone()
        val link = r.factory.links.first()
        link.onConnectionState!!("disconnected")
        link.onConnectionState!!("connected")
        delay(80)
        assertFalse(r.signal.methods.contains("media.restartIce"))
        link.onConnectionState!!("disconnected")
        until("the restart") { r.signal.methods.contains("media.restartIce") }
        until("the new offer") { link.log.any { it.op == "createOffer" && it.type == "restart" } }
    }

    @Test fun `a failed path is restarted at once, and a pending restart is dropped when the call is left`() = runBlocking {
        val r = Rig(graceMs = 30_000)
        r.call.join()
        r.call.enableMicrophone()
        r.factory.links.first().onConnectionState!!("failed")
        until("the restart") { r.signal.methods.contains("media.restartIce") }

        val r2 = Rig(graceMs = 60)
        r2.call.join()
        r2.call.enableMicrophone()
        r2.factory.links.first().onConnectionState!!("disconnected")
        r2.call.leave()
        delay(200)
        assertFalse(r2.signal.methods.contains("media.restartIce"))
    }

    @Test fun `after a reconnect it takes its place again, restarts the paths, and sees who is here now`() = runBlocking {
        val r = Rig()
        val log = CopyOnWriteArrayList<String>()
        r.call.onPeerJoined = { log += "+${it.identity}" }
        r.call.join()
        r.call.enableMicrophone()
        r.signal.line(RealtimeState.RECONNECTING)
        until("reconnecting") { r.call.state == CallState.RECONNECTING }
        r.signal.answers["media.join"] = { mapOf("roomId" to "room-1", "identity" to "me", "routerRtpCapabilities" to g["routerRtpCapabilities"], "iceServers" to emptyList<Any>(), "role" to "speaker", "peers" to listOf(mapOf("identity" to "bola", "role" to "speaker", "producers" to emptyList<Any>()))) }
        r.signal.line(RealtimeState.CONNECTED)
        until("joined again") { r.call.state == CallState.JOINED && log.contains("+bola") }
        assertContains(r.signal.methods, "media.restartIce")
    }

    @Test fun `the server no longer having this person, or the line closing for good, ends the call`() = runBlocking {
        val r = Rig()
        val ended = CopyOnWriteArrayList<String>()
        r.call.onEnded = { ended += it }
        r.call.join()
        r.signal.line(RealtimeState.RECONNECTING)
        until("reconnecting") { r.call.state == CallState.RECONNECTING }
        r.signal.answers["media.join"] = { throw CallException("gone", "no_route") }
        r.signal.line(RealtimeState.CONNECTED)
        until("the end") { ended.isNotEmpty() }
        assertEquals(listOf("no_route"), ended.toList())

        val r2 = Rig()
        val ended2 = CopyOnWriteArrayList<String>()
        r2.call.onEnded = { ended2 += it }
        r2.call.join()
        r2.signal.line(RealtimeState.CLOSED)
        until("the end") { ended2.isNotEmpty() }
        assertEquals(listOf("disconnected"), ended2.toList())
    }

    @Test fun `leaving tells the server, lets go of the devices, closes the transports and the line, and twice does nothing`() = runBlocking {
        val r = Rig()
        r.call.leave()
        r.call.join()
        val t = r.call.enableMicrophone() as FakeTrack
        r.call.leave()
        r.call.leave()
        assertEquals(1, r.signal.methods.count { it == "media.leave" })
        assertTrue(t.stopped)
        assertTrue(r.signal.disconnected)
        assertTrue(r.factory.links.all { it.closed })
        assertEquals(CallState.CLOSED, r.call.state)
    }

    @Test fun `stats add up what is arriving, per track`() = runBlocking {
        val r = Rig(join = mapOf("peers" to listOf(ada())))
        r.call.join()
        until("the tracks") { runBlocking { r.call.stats().size == 2 } }
        val stats = r.call.stats()
        assertEquals(setOf(TrackSource.AUDIO, TrackSource.VIDEO), stats.map { it.source }.toSet())
        assertTrue(stats.all { it.bytesReceived == 5000L })
    }

    private fun joinWith(peers: List<Map<String, Any?>>): Map<String, Any?> =
        mapOf("roomId" to "room-1", "identity" to "me", "routerRtpCapabilities" to g["routerRtpCapabilities"], "iceServers" to emptyList<Any>(), "role" to "speaker", "peers" to peers)

    @Test fun `finds a person and their track that the room events never told it about`() = runBlocking {
        val r = Rig(resyncMs = 20)
        val log = CopyOnWriteArrayList<String>()
        r.call.onPeerJoined = { log += "+${it.identity}" }
        r.call.onTrack = { log += "track:${it.source.wire}" }
        r.call.join()
        r.signal.answers["media.join"] = { joinWith(listOf(mapOf("identity" to "ada", "role" to "host", "producers" to listOf(mapOf("id" to "p-a", "kind" to "audio", "paused" to false))))) }
        until("the catch-up") { log.size >= 2 }
        assertEquals(listOf("+ada", "track:audio"), log.toList())
        r.call.leave()
    }

    @Test fun `notices that someone has gone without hearing about it`() = runBlocking {
        val r = Rig(join = mapOf("peers" to listOf(ada())), resyncMs = 20)
        val log = CopyOnWriteArrayList<String>()
        r.call.onPeerLeft = { log += "-$it" }
        r.call.onTrackEnded = { log += "ended:${it.source.wire}" }
        r.call.join()
        until("the tracks") { runBlocking { r.call.stats().size == 2 } }
        r.signal.answers["media.join"] = { joinWith(emptyList()) }
        until("the catch-up") { log.size >= 3 }
        assertEquals(setOf("-ada", "ended:audio", "ended:video"), log.toSet())
        r.call.leave()
    }

    @Test fun `stops asking once the call is left, is not ended by a question that fails, and can be turned off`() = runBlocking {
        val r = Rig(resyncMs = 20)
        r.call.join()
        r.signal.answers["media.join"] = { throw CallException("busy", "internal") }
        delay(120)
        assertEquals(CallState.JOINED, r.call.state)
        r.call.leave()
        val asked = r.signal.methods.count { it == "media.join" }
        delay(120)
        assertEquals(asked, r.signal.methods.count { it == "media.join" })

        val off = Rig()
        off.call.join()
        val before = off.signal.methods.count { it == "media.join" }
        delay(120)
        assertEquals(before, off.signal.methods.count { it == "media.join" })
        off.call.leave()
    }

    @Test fun `a malformed room event is reported and does not end the call or the app`() = runBlocking {
        val r = Rig()
        val errors = CopyOnWriteArrayList<String>()
        r.call.onError = { errors += it.message ?: "error" }
        r.call.join()
        // A new track announced without saying which one: reading it fails.
        r.signal.room("newProducer", mapOf("identity" to "ada"))
        until("the problem to be reported") { errors.isNotEmpty() }
        assertEquals(CallState.JOINED, r.call.state)
        // And the call still works afterwards.
        r.signal.room("newProducer", mapOf("identity" to "ada", "producerId" to "p-a", "kind" to "audio"))
        until("the next event to be handled") { runBlocking { r.call.stats().size == 1 } }
        r.call.leave()
    }
}
