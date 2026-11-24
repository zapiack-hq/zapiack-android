package com.zapiack.calls

class FakeTrack(override val kind: String, override val id: String = "track-$kind") : MediaTrack {
    override var enabled = true
    var stopped = false
    override fun stop() { stopped = true }
    override val native: Any get() = this
    override var onEnded: (() -> Unit)? = null
}

class FakeTransceiver(override val mid: String, override val receiverTrack: MediaTrack? = null) : LinkTransceiver {
    var stopped = false
    var replaced: MediaTrack? = null
    override suspend fun stop() { stopped = true }
    override suspend fun replaceTrack(track: MediaTrack?) { replaced = track }
    override suspend fun getStats(): List<Map<String, Any?>> = listOf(mapOf("type" to "inbound-rtp", "bytesReceived" to 5000L, "packetsReceived" to 50L, "packetsLost" to 1L, "jitter" to 0.01))
}

data class LinkLog(val op: String, val type: String, val sdp: String)

/** A peer connection that answers from a script: the offers and answers a real engine made, recorded in the golden file. */
class FakeLink(private val offers: List<String> = emptyList(), private val answers: List<String> = emptyList(), private val offerBySequence: Map<String, String>? = null) : PeerLink {
    private var o = 0
    private var a = 0
    private val sequence = StringBuilder()
    val log = ArrayList<LinkLog>()
    private var local: String? = null
    val added = ArrayList<FakeTransceiver>()
    @Volatile var closed = false
    override var onConnectionState: ((String) -> Unit)? = null

    override suspend fun createOffer(iceRestart: Boolean): String {
        log += LinkLog("createOffer", if (iceRestart) "restart" else "", "")
        offerBySequence?.let { return it[sequence.toString()] ?: it.values.last() }
        return offers[if (o < offers.size) o++ else offers.size - 1]
    }

    override suspend fun createAnswer(): String = answers[if (a < answers.size) a++ else answers.size - 1]

    override suspend fun setLocalDescription(type: String, sdp: String) { log += LinkLog("setLocal", type, sdp); local = sdp }
    override suspend fun setRemoteDescription(type: String, sdp: String) { log += LinkLog("setRemote", type, sdp) }
    override suspend fun localSdp(): String? = local

    override suspend fun addSendTransceiver(track: MediaTrack, streamId: String, maxBitrate: Int?): LinkTransceiver {
        sequence.append(if (track.kind == "audio") 'a' else 'v')
        return FakeTransceiver("${added.size}").also { added += it }
    }

    override suspend fun transceiverByMid(mid: String): LinkTransceiver? = FakeTransceiver(mid, FakeTrack("audio", "rx-$mid"))
    override suspend fun getStats(): List<Map<String, Any?>> = emptyList()
    override suspend fun close() { closed = true }
}

class FakeFactory(private val native: String, private val offers: List<String> = emptyList(), private val answers: List<String> = emptyList(), private val offerBySequence: Map<String, String>? = null) : LinkFactory {
    val links = ArrayList<FakeLink>()
    override fun create(iceServers: List<Map<String, Any?>>, iceTransportPolicy: String): PeerLink = FakeLink(offers, answers, offerBySequence).also { links += it }
    override suspend fun nativeOffer(): String = native
}
