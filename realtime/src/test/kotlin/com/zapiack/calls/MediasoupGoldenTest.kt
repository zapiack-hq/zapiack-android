package com.zapiack.calls

import kotlinx.coroutines.runBlocking
import com.google.gson.Gson
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * The Kotlin client against the JavaScript one. `mediasoup_golden.json` holds what the real mediasoup-client computed for the
 * capabilities of a real mediasoup router and the SDP of a real Chrome (see tool/golden in the Flutter package). The same inputs go
 * into this port and the answers must be identical, down to the characters of the SDP the server's side is described with.
 */
/** Compares two SDPs line by line, so a failure names the first line that differs. */
internal fun assertSdp(expected: Any?, actual: String, message: String) {
    val e = (expected as String).split("\r\n")
    val a = actual.split("\r\n")
    for (i in 0 until maxOf(e.size, a.size)) assertEquals(e.getOrNull(i), a.getOrNull(i), "$message: line ${i + 1}")
}

class MediasoupGoldenTest {
    private val g: Json = normalize(Gson().fromJson(javaClass.getResource("/mediasoup_golden.json")!!.readText(), LinkedHashMap::class.java)).obj()
    private fun c(v: Any?): Json = deepCopy(v.obj())
    private fun sdp(name: String) = g["sdps"].obj()[name] as String

    @Test fun `reads the native capabilities out of a real Chrome offer`() {
        assertEquals(g["nativeCaps"], localRtpCapabilities(parseSdp(sdp("native"))))
    }

    @Test fun `matches them against the router, the shared codecs, RTX and header extensions, in the router's order`() {
        assertEquals(g["extended"], getExtendedRtpCapabilities(c(g["nativeCaps"]), c(g["routerRtpCapabilities"])))
    }

    @Test fun `says what this phone can receive`() {
        val ext = getExtendedRtpCapabilities(c(g["nativeCaps"]), c(g["routerRtpCapabilities"]))
        assertEquals(g["recvCaps"], getRecvRtpCapabilities(ext))
    }

    private fun sendHandler(link: FakeLink, connects: MutableList<Json>): Handler {
        val send = g["send"].obj()
        val tp = send["transportParams"].obj()
        return Handler("send", c(tp["iceParameters"]), deepCopyList(tp["iceCandidates"].arr()), c(tp["dtlsParameters"]), link, { native -> getExtendedRtpCapabilities(native, c(g["routerRtpCapabilities"])) }, { connects += it })
    }

    @Test fun `every send step produces the same RTP parameters and the same remote SDP as mediasoup-client`() = runBlocking {
        val steps = g["send"].obj()["steps"].objs()
        val link = FakeLink(offers = steps.map { it["offerSdp"] as String })
        val connects = ArrayList<Json>()
        val h = sendHandler(link, connects)
        for (step in steps) {
            val kind = step["kind"] as String
            val r = h.send(FakeTrack(kind), "stream-1", null, (step["codecOptions"] as? Map<*, *>)?.let { deepCopy(it.obj()) })
            assertEquals(step["expectParams"], r.rtpParameters, "$kind parameters")
            assertSdp(step["expectRemoteSdp"], link.log.last { it.op == "setRemote" && it.type == "answer" }.sdp, "$kind remote SDP")
        }
        assertEquals(listOf(g["send"].obj()["dtlsParameters"]), connects, "the transport connects once, with the engine's DTLS parameters")
    }

    @Test fun `restarting ICE swaps in the new credentials and negotiates again`() = runBlocking {
        val steps = g["send"].obj()["steps"].objs()
        val link = FakeLink(offers = steps.map { it["offerSdp"] as String })
        val h = sendHandler(link, ArrayList())
        for (step in steps) h.send(FakeTrack(step["kind"] as String), "stream-1", null, (step["codecOptions"] as? Map<*, *>)?.let { deepCopy(it.obj()) })
        h.restartIce(c(g["restart"].obj()["iceParameters"]))
        assertSdp(g["restart"].obj()["expectRemoteSdp"], link.log.last().sdp, "after restarting ICE")
        assertEquals("restart", link.log.last { it.op == "createOffer" }.type)
    }

    private fun recvHandler(link: FakeLink, connects: MutableList<Json>): Handler {
        val recv = g["recv"].obj()
        val tp = recv["transportParams"].obj()
        return Handler("recv", c(tp["iceParameters"]), deepCopyList(tp["iceCandidates"].arr()), c(tp["dtlsParameters"]), link, { error("a receiving transport never sends") }, { connects += it })
    }

    @Test fun `describes each track the server will send the way mediasoup-client does`() = runBlocking {
        val recv = g["recv"].obj()
        val link = FakeLink(answers = listOf(recv["answerSdp"] as String))
        val h = recvHandler(link, ArrayList())
        val consumers = recv["consumers"].objs()
        val steps = recv["steps"].objs()
        for ((i, k) in consumers.withIndex()) {
            val out = h.receive(listOf(ReceiveItem(k["id"] as String, k["kind"] as String, c(k["rtpParameters"]))))
            assertEquals(steps[i]["mid"], out.single().localId)
            assertSdp(steps[i]["expectRemoteOffer"], link.log.last { it.op == "setRemote" && it.type == "offer" }.sdp, "offer for consumer $i")
        }
    }

    @Test fun `reads the DTLS parameters the engine put in its answer, and connects once as the client`() = runBlocking {
        val recv = g["recv"].obj()
        val link = FakeLink(answers = listOf(recv["answerSdp"] as String))
        val connects = ArrayList<Json>()
        recvHandler(link, connects).receive(recv["consumers"].objs().map { ReceiveItem(it["id"] as String, it["kind"] as String, c(it["rtpParameters"])) })
        assertEquals(listOf(recv["expectDtls"]), connects)
    }

    @Test fun `keeps what the engine answered, and changes only the Opus lines the sender asked for`() = runBlocking {
        val recv = g["recv"].obj()
        val link = FakeLink(answers = listOf(recv["answerSdp"] as String))
        recvHandler(link, ArrayList()).receive(recv["consumers"].objs().map { ReceiveItem(it["id"] as String, it["kind"] as String, c(it["rtpParameters"])) })
        val sent = parseSdp(link.log.first { it.op == "setLocal" }.sdp)
        val expected = parseSdp(recv["expectAnswerAfterCodecParameters"] as String)
        for ((i, b) in expected["media"].objs().withIndex()) {
            val a = sent["media"].objs()[i]
            assertEquals(b["mid"], a["mid"])
            assertEquals(b["rtp"], a["rtp"])
            assertEquals(b["fmtp"], a["fmtp"])
            assertEquals(b["rtcpFb"], a["rtcpFb"])
        }
    }
}
