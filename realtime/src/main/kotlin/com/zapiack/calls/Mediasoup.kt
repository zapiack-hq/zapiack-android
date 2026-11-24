package com.zapiack.calls

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * The mediasoup client's public pieces: a Device that knows what this phone can do, Transports that carry media, and the Producers and
 * Consumers on them. Written for Zapiack against the mediasoup protocol, with no mediasoup library behind it.
 *
 * Derived from mediasoup-client (ISC licence, Copyright (c) 2015, Iñaki Baz Castillo and contributors).
 */

/** What the phone and the router can both do. */
internal class Device(private val factory: LinkFactory) {
    private var router: Json? = null
    private var recvCaps: Json? = null

    val loaded: Boolean get() = recvCaps != null

    /** What this phone can receive, to hand to the server when asking for a track. */
    val rtpCapabilities: Json get() = recvCaps ?: throw IllegalStateException("The device is not loaded.")

    /** Learns what the engine can do and matches it with the router's capabilities (from `media.join`). */
    suspend fun load(routerRtpCapabilities: Json) {
        check(!loaded) { "The device is already loaded." }
        val r = deepCopy(routerRtpCapabilities)
        validateAndNormalizeRtpCapabilities(r)
        val native = localRtpCapabilities(parseSdp(factory.nativeOffer()))
        val ext = getExtendedRtpCapabilities(deepCopy(native), deepCopy(r))
        val recv = getRecvRtpCapabilities(ext)
        validateAndNormalizeRtpCapabilities(recv)
        router = r
        recvCaps = recv
    }

    /** Whether the router has a codec for [kind] that this phone can send. */
    fun canProduce(kind: String): Boolean {
        check(loaded) { "The device is not loaded." }
        return router!!["codecs"].objs().any { it["kind"] == kind }
    }

    fun createTransport(direction: String, o: TransportOptions): Transport {
        check(loaded) { "The device is not loaded." }
        return Transport(this, factory, direction, o)
    }

    internal fun routerCapabilities(): Json = deepCopy(router!!)
}

/** Everything the server said about a transport, plus where to connect through. */
internal class TransportOptions(
    val id: String,
    val iceParameters: Json,
    val iceCandidates: List<Any?>,
    val dtlsParameters: Json,
    val iceServers: List<Map<String, Any?>> = emptyList(),
    val iceTransportPolicy: String = "all",
)

internal class Transport(private val device: Device, factory: LinkFactory, val direction: String, o: TransportOptions) {
    val id: String = o.id
    private val link: PeerLink = factory.create(o.iceServers, o.iceTransportPolicy)
    private val handler: Handler
    private val gate = Mutex()
    private val producers = HashMap<String, Producer>()
    private val consumers = HashMap<String, Consumer>()

    /** `new`, `connecting`, `connected`, `disconnected`, `failed` or `closed`. */
    @Volatile var connectionState: String = "new"
    @Volatile var closed: Boolean = false
        private set

    /** Tell the server to connect this transport with these DTLS parameters. Called once, the first time anything uses it. */
    var onConnect: (suspend (Json) -> Unit)? = null

    /** Tell the server a track is being sent; it answers with the id it gave it. Only on a sending transport. */
    var onProduce: (suspend (kind: String, rtpParameters: Json, appData: Json) -> String)? = null
    var onConnectionStateChange: ((String) -> Unit)? = null

    init {
        handler = Handler(
            direction = direction,
            iceParameters = deepCopy(o.iceParameters),
            iceCandidates = deepCopyList(o.iceCandidates),
            dtlsParameters = deepCopy(o.dtlsParameters),
            link = link,
            getSendExtendedRtpCapabilities = { native -> getExtendedRtpCapabilities(native, device.routerCapabilities()) },
            onConnect = { dtls -> (onConnect ?: throw IllegalStateException("onConnect is not set: the transport cannot be connected."))(dtls) },
        )
        link.onConnectionState = { s ->
            if (!closed) {
                connectionState = s
                onConnectionStateChange?.invoke(s)
            }
        }
    }

    /** Runs one thing at a time on the connection: negotiation steps must not overlap. */
    internal suspend fun <T> run(task: suspend () -> T): T = gate.withLock { task() }

    /** Sends [track]. [appData] travels to the server with it (the app says what the track is: microphone, camera or screen). */
    suspend fun produce(track: MediaTrack, appData: Json = json(), maxBitrate: Int? = null, codecOptions: Json? = null, streamId: String = "zapiack"): Producer {
        check(!closed) { "The transport is closed." }
        check(direction == "send") { "Not a sending transport." }
        val onProduce = this.onProduce ?: throw IllegalStateException("onProduce is not set.")
        require(track.kind == "audio" || track.kind == "video") { "The track must be audio or video." }
        check(device.canProduce(track.kind)) { "Cannot send ${track.kind}: the server has no codec for it." }
        return run {
            val r = handler.send(track, streamId, maxBitrate, codecOptions)
            try {
                val id = onProduce(track.kind, r.rtpParameters, deepCopy(appData))
                Producer(this, id, r.localId, track, appData).also { producers[id] = it }
            } catch (e: Throwable) {
                // The server refused it: take the section back out so the connection is as it was.
                runCatching { handler.stopSending(r.localId) }
                throw e
            }
        }
    }

    /** Receives a track the server describes: the answer to `media.consume`. */
    suspend fun consume(id: String, producerId: String, kind: String, rtpParameters: Json, appData: Json = json()): Consumer {
        check(!closed) { "The transport is closed." }
        check(direction == "recv") { "Not a receiving transport." }
        val params = deepCopy(rtpParameters)
        check(canReceive(params, device.rtpCapabilities)) { "Cannot receive this track: the codec is not one this phone can take." }
        return run {
            val out = handler.receive(listOf(ReceiveItem(id, kind, params))).single()
            Consumer(this, id, out.localId, producerId, kind, out.track, appData).also { consumers[id] = it }
        }
    }

    /** Negotiates again with fresh ICE parameters from the server: the way back after the network changed. */
    suspend fun restartIce(iceParameters: Json) {
        check(!closed) { "The transport is closed." }
        run { handler.restartIce(deepCopy(iceParameters)) }
    }

    suspend fun getStats(): List<Map<String, Any?>> = handler.getTransportStats()

    suspend fun close() {
        if (closed) return
        closed = true
        connectionState = "closed"
        for (p in producers.values.toList()) p.transportClosed()
        for (c in consumers.values.toList()) c.transportClosed()
        producers.clear()
        consumers.clear()
        handler.close()
    }

    internal fun dropProducer(id: String) { producers.remove(id) }
    internal fun dropConsumer(id: String) { consumers.remove(id) }
    internal fun handlerRef(): Handler = handler
}

internal class Producer(private val t: Transport, val id: String, private val localId: String, val track: MediaTrack, val appData: Json) {
    @Volatile var closed: Boolean = false
        private set
    @Volatile var paused: Boolean = false
        private set
    val kind: String get() = track.kind

    /** Called when the transport it was on has closed. */
    var onTransportClose: (() -> Unit)? = null

    /** Sends nothing while paused (silence, or a frozen picture), without renegotiating. */
    fun pause() {
        if (closed) return
        paused = true
        track.enabled = false
    }

    fun resume() {
        if (closed) return
        paused = false
        track.enabled = true
    }

    /** Stops sending and releases the track and the device behind it. */
    suspend fun close() {
        if (closed) return
        closed = true
        t.dropProducer(id)
        track.stop()
        if (!t.closed) runCatching { t.run { t.handlerRef().stopSending(localId) } }
    }

    internal fun transportClosed() {
        if (closed) return
        closed = true
        track.stop()
        onTransportClose?.invoke()
    }
}

internal class Consumer(private val t: Transport, val id: String, private val localId: String, val producerId: String, val kind: String, val track: MediaTrack, val appData: Json) {
    @Volatile var closed: Boolean = false
        private set
    var onTransportClose: (() -> Unit)? = null

    /** Per-stream numbers from the engine: the report entries for this track. */
    suspend fun getStats(): List<Map<String, Any?>> = t.handlerRef().getReceiverStats(localId)

    suspend fun close() {
        if (closed) return
        closed = true
        t.dropConsumer(id)
        if (!t.closed) runCatching { t.run { t.handlerRef().stopReceiving(listOf(localId)) } }
    }

    internal fun transportClosed() {
        if (closed) return
        closed = true
        onTransportClose?.invoke()
    }
}
