package com.zapiack.calls

/*
 * One WebRTC peer connection driven the way the mediasoup server expects: every track the phone sends is a section in an offer the phone
 * makes and the "remote" answer is built from what the server said it will receive; every track it receives is a section in an offer
 * built from what the server will send, answered by the engine.
 *
 * Derived from mediasoup-client's Chrome111 handler (ISC licence, Copyright (c) 2015, Iñaki Baz Castillo and contributors).
 */

internal class SendResult(val localId: String, val rtpParameters: Json, val transceiver: LinkTransceiver)

internal class ReceiveItem(val trackId: String, val kind: String, val rtpParameters: Json)

internal class ReceiveResult(val localId: String, val track: MediaTrack, val transceiver: LinkTransceiver)

/** What an SDP says the engine can send or receive. */
internal fun extractRtpCapabilities(sdp: Json): Json {
    val codecs = LinkedHashMap<Long, Json>()
    val exts = LinkedHashMap<Long, Json>()
    for (m in sdp["media"].objs()) {
        val kind = m["type"] as String
        if (kind != "audio" && kind != "video") continue
        for (r in m["rtp"].objs()) {
            codecs[r["payload"] as Long] = jsonNoNulls(
                "kind" to kind,
                "mimeType" to "$kind/${r["codec"]}",
                "preferredPayloadType" to r["payload"],
                "clockRate" to r["rate"],
                "channels" to r["encoding"],
                "parameters" to json(),
                "rtcpFeedback" to mutableListOf<Any?>(),
            )
        }
        for (f in m["fmtp"].objs()) {
            val c = codecs[f["payload"] as Long] ?: continue
            val p = parseParams(f["config"] as String)
            if (p.containsKey("profile-level-id")) p["profile-level-id"] = "${p["profile-level-id"]}"
            c["parameters"] = p
        }
        for (fb in m["rtcpFb"].objs()) {
            val feedback = jsonNoNulls("type" to fb["type"], "parameter" to (fb["subtype"]?.takeIf { it != "" }))
            if (fb["payload"] != "*") {
                val c = codecs[fb["payload"] as Long] ?: continue
                c["rtcpFeedback"].arr().add(feedback)
            } else {
                for (c in codecs.values) if (c["kind"] == kind && !isRtxCodec(c)) c["rtcpFeedback"].arr().add(deepCopy(feedback))
            }
        }
        for (e in m["ext"].objs()) {
            if (e["encrypt-uri"] != null) continue
            exts[e["value"] as Long] = json("kind" to kind, "uri" to e["uri"], "preferredId" to e["value"])
        }
    }
    return json("codecs" to codecs.values.toMutableList<Any?>(), "headerExtensions" to exts.values.toMutableList<Any?>())
}

/** Native capabilities of this engine, read from an SDP offer it made. */
internal fun localRtpCapabilities(sdp: Json, extraHeaderExtensions: List<Json> = emptyList()): Json {
    val caps = extractRtpCapabilities(sdp)
    validateAndNormalizeRtpCapabilities(caps)
    addNackSupportForOpus(caps)
    for (e in extraHeaderExtensions) addHeaderExtensionSupport(caps, e)
    return caps
}

/** The DTLS role and fingerprint the engine put in its SDP. */
internal fun extractDtlsParameters(sdp: Json): Json {
    var setup = sdp["setup"]
    var fingerprint = sdp["fingerprint"]
    if (setup == null || fingerprint == null) {
        val m = sdp["media"].objs().firstOrNull { it["port"] != 0L }
        if (m != null) {
            if (setup == null) setup = m["setup"]
            if (fingerprint == null) fingerprint = m["fingerprint"]
        }
    }
    if (setup == null) throw IllegalStateException("no a=setup found at SDP session or media level")
    if (fingerprint == null) throw IllegalStateException("no a=fingerprint found at SDP session or media level")
    val role = when (setup) { "active" -> "client"; "passive" -> "server"; else -> "auto" }
    return json("role" to role, "fingerprints" to mutableListOf<Any?>(json("algorithm" to fingerprint.obj()["type"], "value" to fingerprint.obj()["hash"])))
}

internal fun getCname(offerMediaObject: Json): String = offerMediaObject["ssrcs"].objs().firstOrNull { it["attribute"] == "cname" }?.get("value")?.toString() ?: ""

/** One encoding per SSRC, with the RTX stream joined to the stream it repairs. */
internal fun getRtpEncodings(offerMediaObject: Json): MutableList<Any?> {
    val ssrcs = LinkedHashSet<Long>()
    for (l in offerMediaObject["ssrcs"].objs()) (l["id"] as? Long)?.let { ssrcs += it }
    if (ssrcs.isEmpty()) throw IllegalStateException("no a=ssrc lines found")
    val toRtx = LinkedHashMap<Long, Long?>()
    for (l in offerMediaObject["ssrcGroups"].objs()) {
        if (l["semantics"] != "FID") continue
        val parts = (l["ssrcs"] as String).split(Regex("\\s+"))
        val ssrc = parts[0].toLong()
        val rtx = parts[1].toLong()
        if (ssrc in ssrcs) {
            ssrcs -= ssrc
            ssrcs -= rtx
            toRtx[ssrc] = rtx
        }
    }
    for (s in ssrcs) toRtx[s] = null
    return toRtx.entries.mapTo(ArrayList<Any?>()) { e -> jsonNoNulls("ssrc" to e.key, "rtx" to (e.value?.takeIf { it != 0L }?.let { json("ssrc" to it) })) }
}

/** The Opus options the answer must repeat: stereo, as the sender said. */
internal fun applyCodecParameters(offerRtpParameters: Json, answerMediaObject: Json) {
    for (codec in offerRtpParameters["codecs"].objs()) {
        if ((codec["mimeType"] as String).lowercase() != "audio/opus") continue
        answerMediaObject["rtp"].objs().firstOrNull { it["payload"] == codec["payloadType"] } ?: continue
        val fmtps = answerMediaObject.list("fmtp")
        var fmtp = fmtps.objs().firstOrNull { it["payload"] == codec["payloadType"] }
        if (fmtp == null) {
            fmtp = json("payload" to codec["payloadType"], "config" to "")
            fmtps += fmtp
        }
        val params = parseParams(fmtp["config"] as String)
        val stereo = (codec["parameters"] as? Map<*, *>)?.obj()?.get("sprop-stereo")
        if (stereo != null) params["stereo"] = if (if (stereo is Number) stereo.toDouble() != 0.0 else "$stereo" != "0" && "$stereo" != "") 1L else 0L
        fmtp["config"] = params.entries.joinToString(";") { "${it.key}=${it.value}" }
    }
}

/** Sets one `a=fmtp` line inside the media section with [mid], keeping every other line. */
private fun setFmtp(sdp: String, mid: String, payload: Long, config: String): String {
    val lines = sdp.split("\r\n").toMutableList()
    var start = -1
    var end = lines.size
    for (i in lines.indices) {
        if (lines[i] == "a=mid:$mid") {
            for (j in i downTo 0) if (lines[j].startsWith("m=")) { start = j; break }
            for (j in start + 1 until lines.size) if (lines[j].startsWith("m=")) { end = j; break }
            break
        }
    }
    if (start < 0) return sdp
    val line = "a=fmtp:$payload $config"
    for (i in start until end) if (lines[i].startsWith("a=fmtp:$payload ")) { lines[i] = line; return lines.joinToString("\r\n") }
    // No line yet: it goes straight after the `a=rtpmap` of that payload.
    for (i in start until end) if (lines[i].startsWith("a=rtpmap:$payload ")) { lines.add(i + 1, line); return lines.joinToString("\r\n") }
    return sdp
}

internal class Handler(
    val direction: String,
    iceParameters: Json,
    iceCandidates: List<Any?>,
    dtlsParameters: Json,
    private val link: PeerLink,
    private val getSendExtendedRtpCapabilities: (Json) -> Json,
    private val onConnect: suspend (Json) -> Unit,
) {
    private val remote = RemoteSdp(iceParameters, iceCandidates, dtlsParameters)
    private val forcedLocalDtlsRole: String? = (dtlsParameters["role"] as? String)?.takeIf { it != "auto" }?.let { if (it == "server") "client" else "server" }
    private val byMid = HashMap<String, LinkTransceiver>()
    private var ready = false
    private var closed = false

    suspend fun close() {
        if (closed) return
        closed = true
        runCatching { link.close() }
    }

    private suspend fun setupTransport(localSdp: Json) {
        val dtls = extractDtlsParameters(localSdp)
        val role = forcedLocalDtlsRole ?: "client"
        dtls["role"] = role
        remote.updateDtlsRole(if (role == "client") "server" else "client")
        onConnect(dtls)
        ready = true
    }

    suspend fun restartIce(iceParameters: Json) {
        remote.updateIceParameters(iceParameters)
        if (!ready) return
        if (direction == "send") {
            link.setLocalDescription("offer", link.createOffer(iceRestart = true))
            link.setRemoteDescription("answer", remote.getSdp())
        } else {
            link.setRemoteDescription("offer", remote.getSdp())
            link.setLocalDescription("answer", link.createAnswer())
        }
    }

    suspend fun getTransportStats(): List<Map<String, Any?>> = link.getStats()

    /** Starts sending [track]. [streamId] groups a person's tracks. */
    suspend fun send(track: MediaTrack, streamId: String, maxBitrate: Int?, codecOptions: Json?): SendResult {
        check(direction == "send") { "not a sending transport" }
        val idx = remote.nextMediaSectionIdx()
        val transceiver = link.addSendTransceiver(track, streamId, maxBitrate)
        val offer = link.createOffer()
        var local = parseSdp(offer)
        if (local["extmapAllowMixed"] != null) remote.setSessionExtmapAllowMixed()
        val native = localRtpCapabilities(local, listOf(json("uri" to "http://www.webrtc.org/experiments/rtp-hdrext/abs-capture-time", "kind" to track.kind, "direction" to "sendonly")))
        val ext = getSendExtendedRtpCapabilities(native)
        val params = getSendingRtpParameters(track.kind, ext)
        params["codecs"] = reduceCodecs(params["codecs"].objs())
        val remoteParams = getSendingRemoteRtpParameters(track.kind, ext)
        remoteParams["codecs"] = reduceCodecs(remoteParams["codecs"].objs())
        if (!ready) setupTransport(local)
        link.setLocalDescription("offer", offer)
        local = parseSdp(link.localSdp() ?: offer)
        val media = local["media"].objs()[idx.first]
        val localId = "${media["mid"]}"
        params["mid"] = localId
        params["rtcp"].obj()["cname"] = getCname(media)
        params["msid"] = "$streamId ${track.id}"
        params["encodings"] = getRtpEncodings(media)
        remote.send(media, idx.second, params, remoteParams, codecOptions)
        link.setRemoteDescription("answer", remote.getSdp())
        byMid[localId] = transceiver
        return SendResult(localId, params, transceiver)
    }

    suspend fun stopSending(localId: String) {
        if (closed) return
        val t = byMid[localId] ?: throw IllegalStateException("associated transceiver not found")
        t.replaceTrack(null)
        val closedSection = remote.closeMediaSection(localId)
        if (closedSection) runCatching { t.stop() }
        link.setLocalDescription("offer", link.createOffer())
        link.setRemoteDescription("answer", remote.getSdp())
        byMid.remove(localId)
    }

    suspend fun receive(items: List<ReceiveItem>): List<ReceiveResult> {
        check(direction == "recv") { "not a receiving transport" }
        val localIds = HashMap<String, String>()
        for (it in items) {
            val localId = (it.rtpParameters["mid"] as? String) ?: "${byMid.size}"
            localIds[it.trackId] = localId
            val ms = msidParts(it.rtpParameters["msid"] as? String)
            remote.receive(localId, it.kind, it.rtpParameters, ms.first ?: (it.rtpParameters["rtcp"] as? Map<*, *>)?.obj()?.get("cname") as? String ?: "-", it.trackId)
        }
        link.setRemoteDescription("offer", remote.getSdp())
        val answer = link.createAnswer()
        val local = parseSdp(answer)
        val fixed = withOpusParameters(answer, local, items, localIds)
        if (!ready) setupTransport(local)
        link.setLocalDescription("answer", fixed)
        val out = ArrayList<ReceiveResult>()
        for (it in items) {
            val localId = localIds.getValue(it.trackId)
            val t = link.transceiverByMid(localId) ?: throw IllegalStateException("new transceiver not found")
            byMid[localId] = t
            val track = t.receiverTrack ?: throw IllegalStateException("the received section has no track")
            out += ReceiveResult(localId, track, t)
        }
        return out
    }

    /** The engine's answer with the Opus options the sender asked for. Only the lines that change are rewritten. */
    private fun withOpusParameters(answer: String, local: Json, items: List<ReceiveItem>, localIds: Map<String, String>): String {
        var text = answer
        for (it in items) {
            val m = local["media"].objs().firstOrNull { m -> "${m["mid"]}" == localIds[it.trackId] } ?: continue
            val before = m["fmtp"].objs().map { f -> f["payload"] to f["config"] }
            applyCodecParameters(it.rtpParameters, m)
            for (f in m["fmtp"].objs()) {
                val old = before.firstOrNull { b -> b.first == f["payload"] }
                if (old != null && old.second == f["config"]) continue
                text = setFmtp(text, "${m["mid"]}", f["payload"] as Long, f["config"] as String)
            }
        }
        return text
    }

    suspend fun stopReceiving(localIds: List<String>) {
        if (closed) return
        for (id in localIds) {
            if (!byMid.containsKey(id)) throw IllegalStateException("associated transceiver not found")
            remote.closeMediaSection(id)
        }
        link.setRemoteDescription("offer", remote.getSdp())
        link.setLocalDescription("answer", link.createAnswer())
        for (id in localIds) byMid.remove(id)
    }

    suspend fun getReceiverStats(localId: String): List<Map<String, Any?>> =
        (byMid[localId] ?: throw IllegalStateException("associated transceiver not found")).getStats()
}
