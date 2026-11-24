package com.zapiack.calls

/*
 * The small surface of a WebRTC engine this client needs. The mediasoup logic is written against these, not against an engine, so it
 * runs (and is tested) without a phone; the `zapiack-android` library has the one that talks to the Android WebRTC library.
 */

/** A camera, microphone or screen track, or one that arrives from the server. */
interface MediaTrack {
    val id: String

    /** `audio` or `video`. */
    val kind: String
    var enabled: Boolean

    /** Stops it for good and lets go of the device (the camera light goes off). */
    fun stop()

    /** The engine's own object, handed back to the app to show or play. */
    val native: Any

    /** Called when the track ends by itself: a screen share stopped from the system, or a camera taken by another app. */
    var onEnded: (() -> Unit)?
}

interface LinkTransceiver {
    val mid: String
    suspend fun stop()
    suspend fun replaceTrack(track: MediaTrack?)

    /** The track a received section brings. */
    val receiverTrack: MediaTrack?
    suspend fun getStats(): List<Map<String, Any?>>
}

interface PeerLink {
    suspend fun createOffer(iceRestart: Boolean = false): String
    suspend fun createAnswer(): String
    suspend fun setLocalDescription(type: String, sdp: String)
    suspend fun setRemoteDescription(type: String, sdp: String)
    suspend fun localSdp(): String?

    /** Adds a section that sends [track], in the stream [streamId], capped at [maxBitrate] bits per second. */
    suspend fun addSendTransceiver(track: MediaTrack, streamId: String, maxBitrate: Int?): LinkTransceiver

    /** The section with this mid, once it exists. */
    suspend fun transceiverByMid(mid: String): LinkTransceiver?
    suspend fun getStats(): List<Map<String, Any?>>
    suspend fun close()

    /** `new`, `connecting`, `connected`, `disconnected`, `failed` or `closed`. */
    var onConnectionState: ((String) -> Unit)?
}

/** Makes peer connections. [iceServers] are `{urls, username, credential}`. */
interface LinkFactory {
    fun create(iceServers: List<Map<String, Any?>>, iceTransportPolicy: String): PeerLink

    /** An offer with an audio and a video section and no tracks, from which the engine's own codecs are read. */
    suspend fun nativeOffer(): String
}
