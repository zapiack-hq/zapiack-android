package com.zapiack.calls

import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/* The one place that talks to the Android WebRTC library (org.webrtc). Everything else is written against `Link.kt`. */

internal class WebRtcTrack(private val track: MediaStreamTrack, private val release: () -> Unit = {}) : MediaTrack {
    override val id: String get() = track.id()
    override val kind: String get() = track.kind()
    override var enabled: Boolean
        get() = track.enabled()
        set(v) { track.setEnabled(v) }

    override fun stop() {
        track.setEnabled(false)
        release()
    }

    override val native: Any get() = track
    override var onEnded: (() -> Unit)? = null
}

private fun flatten(report: RTCStatsReport): List<Map<String, Any?>> =
    report.statsMap.values.map { s -> LinkedHashMap<String, Any?>().also { m -> m.putAll(s.members); m["id"] = s.id; m["type"] = s.type } }

private fun connectionState(s: PeerConnection.PeerConnectionState): String = when (s) {
    PeerConnection.PeerConnectionState.NEW -> "new"
    PeerConnection.PeerConnectionState.CONNECTING -> "connecting"
    PeerConnection.PeerConnectionState.CONNECTED -> "connected"
    PeerConnection.PeerConnectionState.DISCONNECTED -> "disconnected"
    PeerConnection.PeerConnectionState.FAILED -> "failed"
    PeerConnection.PeerConnectionState.CLOSED -> "closed"
}

private suspend fun PeerConnection.stats(): List<Map<String, Any?>> = suspendCancellableCoroutine { c -> getStats { c.resume(flatten(it)) } }

/**
 * The engine hands out transceiver objects that it disposes and replaces as negotiation goes on, so this does not hold one: it finds the
 * current one by its `mid` each time it is used.
 */
internal class WebRtcTransceiver(first: RtpTransceiver, private val pc: PeerConnection) : LinkTransceiver {
    private var original: RtpTransceiver = first
    private var knownMid: String = first.mid ?: ""

    private fun current(): RtpTransceiver {
        val found = pc.transceivers.firstOrNull { it === original || (knownMid.isNotEmpty() && it.mid == knownMid) } ?: original
        original = found
        found.mid?.let { knownMid = it }
        return found
    }

    override val mid: String get() = current().mid ?: ""

    override suspend fun stop() = current().stopStandard()

    override suspend fun replaceTrack(track: MediaTrack?) {
        current().sender.setTrack(track?.native as? MediaStreamTrack, false)
    }

    override val receiverTrack: MediaTrack? get() = current().receiver.track()?.let { WebRtcTrack(it) }

    override suspend fun getStats(): List<Map<String, Any?>> {
        // The receiver's own report is asked for first; if the engine leaves it empty, the connection's incoming streams of that kind are used.
        val receiver = current().receiver
        val kind = receiver.track()?.kind()
        val own = suspendCancellableCoroutine<List<Map<String, Any?>>> { c -> pc.getStats(receiver) { c.resume(flatten(it)) } }
        if (own.isNotEmpty()) return own
        return pc.stats().filter { it["type"] == "inbound-rtp" && (it["kind"] ?: it["mediaType"]) == kind }
    }
}

private fun sdpObserver(onOk: (SessionDescription?) -> Unit, onFail: (String) -> Unit) = object : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) = onOk(d)
    override fun onSetSuccess() = onOk(null)
    override fun onCreateFailure(e: String?) = onFail(e ?: "failed")
    override fun onSetFailure(e: String?) = onFail(e ?: "failed")
}

internal class WebRtcLink(private val factory: PeerConnectionFactory, iceServers: List<Map<String, Any?>>, policy: String) : PeerLink {
    @Volatile override var onConnectionState: ((String) -> Unit)? = null
    private var closed = false

    private val pc: PeerConnection = run {
        val servers = iceServers.map { s ->
            val urls = (s["urls"] as? List<*>)?.map { it.toString() } ?: listOf(s["urls"].toString())
            PeerConnection.IceServer.builder(urls).apply {
                (s["username"] as? String)?.let { setUsername(it) }
                (s["credential"] as? String)?.let { setPassword(it) }
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = if (policy == "relay") PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        }
        factory.createPeerConnection(
            config,
            object : PeerConnection.Observer {
                override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { onConnectionState?.invoke(connectionState(s)) }
                override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(b: Boolean) {}
                override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(c: org.webrtc.IceCandidate?) {}
                override fun onIceCandidatesRemoved(c: Array<out org.webrtc.IceCandidate>?) {}
                override fun onAddStream(s: org.webrtc.MediaStream?) {}
                override fun onRemoveStream(s: org.webrtc.MediaStream?) {}
                override fun onDataChannel(d: org.webrtc.DataChannel?) {}
                override fun onRenegotiationNeeded() {}
            },
        ) ?: throw IllegalStateException("The WebRTC engine could not make a connection.")
    }

    private suspend fun description(block: (SdpObserver) -> Unit): String = suspendCancellableCoroutine { c ->
        block(sdpObserver({ d -> c.resume(d?.description ?: "") }, { e -> c.resumeWithException(IllegalStateException(e)) }))
    }

    private suspend fun apply(block: (SdpObserver) -> Unit) {
        suspendCancellableCoroutine<Unit> { c -> block(sdpObserver({ c.resume(Unit) }, { e -> c.resumeWithException(IllegalStateException(e)) })) }
    }

    override suspend fun createOffer(iceRestart: Boolean): String {
        val constraints = MediaConstraints().apply { if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true")) }
        return description { pc.createOffer(it, constraints) }
    }

    override suspend fun createAnswer(): String = description { pc.createAnswer(it, MediaConstraints()) }

    override suspend fun setLocalDescription(type: String, sdp: String) = apply { pc.setLocalDescription(it, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)) }

    override suspend fun setRemoteDescription(type: String, sdp: String) = apply { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)) }

    override suspend fun localSdp(): String? = pc.localDescription?.description

    override suspend fun addSendTransceiver(track: MediaTrack, streamId: String, maxBitrate: Int?): LinkTransceiver {
        val init = RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY,
            listOf(streamId),
            if (maxBitrate == null) emptyList() else listOf(RtpParameters.Encoding(null, true, null).apply { maxBitrateBps = maxBitrate }),
        )
        return WebRtcTransceiver(pc.addTransceiver(track.native as MediaStreamTrack, init), pc)
    }

    override suspend fun transceiverByMid(mid: String): LinkTransceiver? = pc.transceivers.firstOrNull { it.mid == mid }?.let { WebRtcTransceiver(it, pc) }

    override suspend fun getStats(): List<Map<String, Any?>> = pc.stats()

    /** An audio and a video section that only receive: what an offer needs to say what the engine can do. */
    fun addReceiveOnly() {
        val recv = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recv)
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recv)
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        pc.close()
        pc.dispose()
    }
}

internal class WebRtcLinkFactory(private val factory: PeerConnectionFactory) : LinkFactory {
    override fun create(iceServers: List<Map<String, Any?>>, iceTransportPolicy: String): PeerLink = WebRtcLink(factory, iceServers, iceTransportPolicy)

    override suspend fun nativeOffer(): String {
        val link = WebRtcLink(factory, emptyList(), "all")
        return try {
            link.addReceiveOnly()
            link.createOffer()
        } finally {
            link.close()
        }
    }
}
