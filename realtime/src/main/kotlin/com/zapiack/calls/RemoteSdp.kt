package com.zapiack.calls

/*
 * The server's side of the conversation, written as SDP. A phone's WebRTC engine only knows SDP, so everything the mediasoup server says
 * about a transport (ICE, DTLS) and about each track (codecs, SSRCs) is turned into the SDP the engine would have received from a real
 * peer: an answer for each track the phone sends, an offer for each it receives.
 *
 * Derived from mediasoup-client (ISC licence, Copyright (c) 2015, Iñaki Baz Castillo and contributors).
 */

internal class MediaSection(val obj: Json, private val isOffer: Boolean = false) {
    val mid: String get() = "${obj["mid"]}"
    val closed: Boolean get() = obj["port"] == 0L

    fun setIceParameters(ice: Json) {
        obj["iceUfrag"] = ice["usernameFragment"]
        obj["icePwd"] = ice["password"]
    }

    /** A section that describes what the server sends is an offer, and an offer always leaves the DTLS role open (`actpass`). */
    fun setDtlsRole(role: String) {
        obj["setup"] = if (isOffer) "actpass" else when (role) { "client" -> "active"; "server" -> "passive"; else -> "actpass" }
    }

    fun pause() { obj["direction"] = "inactive" }

    fun disable() {
        pause()
        for (k in listOf("ext", "ssrcs", "ssrcGroups", "simulcast", "simulcast_03", "rids", "extmapAllowMixed")) obj.remove(k)
    }

    fun close() {
        disable()
        obj["port"] = 0L
    }

    companion object {
        /** The ICE and DTLS part every section starts with. */
        fun base(ice: Json?, candidates: List<Any?>?, dtls: Json?, offer: Boolean = false): Json {
            val o = json("type" to "", "port" to 0L, "protocol" to "", "payloads" to "", "rtp" to mutableListOf<Any?>(), "fmtp" to mutableListOf<Any?>())
            val s = MediaSection(o, offer)
            if (ice != null) s.setIceParameters(ice)
            if (candidates != null) {
                o["candidates"] = candidates.objs().mapTo(ArrayList<Any?>()) { c ->
                    jsonNoNulls(
                        "foundation" to c["foundation"],
                        "component" to 1L,
                        "ip" to (c["address"] ?: c["ip"]),
                        "port" to c["port"],
                        "priority" to c["priority"],
                        "transport" to c["protocol"],
                        "type" to c["type"],
                        "tcptype" to (c["tcpType"]?.takeIf { it != "" }),
                    )
                }
                o["endOfCandidates"] = "end-of-candidates"
                o["iceOptions"] = "renomination"
            }
            if (dtls != null && dtls["role"] != null) s.setDtlsRole(dtls["role"] as String)
            return o
        }
    }
}

private fun config(params: Json): String = params.entries.joinToString(";") { "${it.key}=${it.value}" }

private fun codecName(codec: Json): String = Regex("^(audio|video)/(.+)", RegexOption.IGNORE_CASE).find(codec["mimeType"] as String)!!.groupValues[2]

/** The answer to a track the phone sends: the server saying what it will receive. */
internal fun answerMediaSection(
    iceParameters: Json?,
    iceCandidates: List<Any?>?,
    dtlsParameters: Json?,
    offerMediaObject: Json,
    offerRtpParameters: Json,
    answerRtpParameters: Json,
    codecOptions: Json?,
): MediaSection {
    val o = MediaSection.base(iceParameters, iceCandidates, dtlsParameters)
    o["mid"] = "${offerMediaObject["mid"]}"
    o["type"] = offerMediaObject["type"]
    o["protocol"] = offerMediaObject["protocol"]
    o["connection"] = json("ip" to "127.0.0.1", "version" to 4L)
    o["port"] = 7L
    o["direction"] = "recvonly"
    val rtp = mutableListOf<Any?>()
    val rtcpFb = mutableListOf<Any?>()
    val fmtp = mutableListOf<Any?>()
    o["rtp"] = rtp; o["rtcpFb"] = rtcpFb; o["fmtp"] = fmtp
    val answerCodecs = answerRtpParameters["codecs"].objs()
    for (codec in answerCodecs) {
        val r = json("payload" to codec["payloadType"], "codec" to codecName(codec), "rate" to codec["clockRate"])
        if ((codec["channels"].long() ?: 0L) > 1) r["encoding"] = codec["channels"]
        rtp += r
        val codecParameters = deepCopy((codec["parameters"] as? Map<*, *>)?.obj() ?: json())
        var feedback = deepCopyList((codec["rtcpFeedback"] as? List<*>)?.toList() ?: emptyList()).objs().toMutableList()
        if (codecOptions != null) {
            val offerCodec = offerRtpParameters["codecs"].objs().first { it["payloadType"] == codec["payloadType"] }
            when ((codec["mimeType"] as String).lowercase()) {
                "audio/opus", "audio/multiopus" -> {
                    val op = offerCodec["parameters"].obj()
                    if (codecOptions["opusStereo"] != null) { val v = if (codecOptions["opusStereo"] == true) 1L else 0L; op["sprop-stereo"] = v; codecParameters["stereo"] = v }
                    if (codecOptions["opusFec"] != null) { val v = if (codecOptions["opusFec"] == true) 1L else 0L; op["useinbandfec"] = v; codecParameters["useinbandfec"] = v }
                    if (codecOptions["opusDtx"] != null) { val v = if (codecOptions["opusDtx"] == true) 1L else 0L; op["usedtx"] = v; codecParameters["usedtx"] = v }
                    codecOptions["opusMaxPlaybackRate"]?.let { codecParameters["maxplaybackrate"] = it }
                    codecOptions["opusMaxAverageBitrate"]?.let { codecParameters["maxaveragebitrate"] = it }
                    codecOptions["opusPtime"]?.let { op["ptime"] = it; codecParameters["ptime"] = it }
                    if (codecOptions["opusNack"] != true) {
                        offerCodec["rtcpFeedback"] = offerCodec["rtcpFeedback"].objs().filter { it["type"] != "nack" || !falsy(it["parameter"]) }.toMutableList<Any?>()
                        feedback = feedback.filter { it["type"] != "nack" || !falsy(it["parameter"]) }.toMutableList()
                    }
                }
                "video/vp8", "video/vp9", "video/h264", "video/h265", "video/av1" -> {
                    codecOptions["videoGoogleStartBitrate"]?.let { codecParameters["x-google-start-bitrate"] = it }
                    codecOptions["videoGoogleMaxBitrate"]?.let { codecParameters["x-google-max-bitrate"] = it }
                    codecOptions["videoGoogleMinBitrate"]?.let { codecParameters["x-google-min-bitrate"] = it }
                }
            }
        }
        val cfg = config(codecParameters)
        if (cfg.isNotEmpty()) fmtp += json("payload" to codec["payloadType"], "config" to cfg)
        for (fb in feedback) rtcpFb += json("payload" to codec["payloadType"], "type" to fb["type"], "subtype" to fb["parameter"])
    }
    o["payloads"] = answerCodecs.joinToString(" ") { "${it["payloadType"]}" }
    val ext = mutableListOf<Any?>()
    o["ext"] = ext
    for (e in answerRtpParameters["headerExtensions"].objs()) {
        if (offerMediaObject["ext"].objs().any { it["uri"] == e["uri"] }) ext += json("uri" to e["uri"], "value" to e["id"])
    }
    if (offerMediaObject["extmapAllowMixed"] == "extmap-allow-mixed") o["extmapAllowMixed"] = "extmap-allow-mixed"
    o["rtcpMux"] = "rtcp-mux"
    o["rtcpRsize"] = "rtcp-rsize"
    return MediaSection(o)
}

/** The offer for a track the phone receives: the server describing what it will send. */
internal fun offerMediaSection(
    iceParameters: Json?,
    iceCandidates: List<Any?>?,
    dtlsParameters: Json?,
    mid: String,
    kind: String,
    offerRtpParameters: Json,
    streamId: String,
    trackId: String,
): MediaSection {
    val o = MediaSection.base(iceParameters, iceCandidates, dtlsParameters, offer = true)
    o["mid"] = mid
    o["type"] = kind
    o["connection"] = json("ip" to "127.0.0.1", "version" to 4L)
    o["protocol"] = "UDP/TLS/RTP/SAVPF"
    o["port"] = 7L
    o["extmapAllowMixed"] = "extmap-allow-mixed"
    o["direction"] = "sendonly"
    val rtp = mutableListOf<Any?>()
    val rtcpFb = mutableListOf<Any?>()
    val fmtp = mutableListOf<Any?>()
    o["rtp"] = rtp; o["rtcpFb"] = rtcpFb; o["fmtp"] = fmtp
    o["msid"] = mutableListOf<Any?>(json("id" to streamId, "appdata" to trackId))
    val codecs = offerRtpParameters["codecs"].objs()
    for (codec in codecs) {
        val r = json("payload" to codec["payloadType"], "codec" to codecName(codec), "rate" to codec["clockRate"])
        if ((codec["channels"].long() ?: 0L) > 1) r["encoding"] = codec["channels"]
        rtp += r
        val cfg = config((codec["parameters"] as? Map<*, *>)?.obj() ?: json())
        if (cfg.isNotEmpty()) fmtp += json("payload" to codec["payloadType"], "config" to cfg)
        for (fb in codec["rtcpFeedback"].objs()) rtcpFb += json("payload" to codec["payloadType"], "type" to fb["type"], "subtype" to fb["parameter"])
    }
    o["payloads"] = codecs.joinToString(" ") { "${it["payloadType"]}" }
    o["ext"] = offerRtpParameters["headerExtensions"].objs().mapTo(ArrayList<Any?>()) { json("uri" to it["uri"], "value" to it["id"]) }
    o["rtcpMux"] = "rtcp-mux"
    o["rtcpRsize"] = "rtcp-rsize"
    val enc = offerRtpParameters["encodings"].objs().firstOrNull() ?: json()
    val ssrc = enc["ssrc"]
    val rtxSsrc = (enc["rtx"] as? Map<*, *>)?.obj()?.get("ssrc")
    val cname = (offerRtpParameters["rtcp"] as? Map<*, *>)?.obj()?.get("cname")
    val ssrcs = mutableListOf<Any?>()
    val groups = mutableListOf<Any?>()
    o["ssrcs"] = ssrcs; o["ssrcGroups"] = groups
    if (ssrc != null && !falsy(cname)) ssrcs += json("id" to ssrc, "attribute" to "cname", "value" to cname)
    if (rtxSsrc != null) {
        if (!falsy(cname)) ssrcs += json("id" to rtxSsrc, "attribute" to "cname", "value" to cname)
        if (ssrc != null) groups += json("semantics" to "FID", "ssrcs" to "$ssrc $rtxSsrc")
    }
    return MediaSection(o, isOffer = true)
}

/**
 * The whole remote SDP for one transport. Sections are only ever added, paused, resumed or closed (a closed one is reused by the next
 * track), so the `m=` lines keep their order, as WebRTC requires.
 */
internal class RemoteSdp(iceParameters: Json, private val iceCandidates: List<Any?>, private val dtls: Json) {
    private var ice: Json = iceParameters
    private val sdp: Json
    private val sections = ArrayList<MediaSection>()
    private val midToIndex = HashMap<String, Int>()
    private var firstMid: String? = null

    init {
        val fp = dtls["fingerprints"].objs().last()
        sdp = json(
            "version" to 0L,
            "origin" to json("address" to "0.0.0.0", "ipVer" to 4L, "netType" to "IN", "sessionId" to "10000", "sessionVersion" to 0L, "username" to "mediasoup-client"),
            "name" to "-",
            "timing" to json("start" to 0L, "stop" to 0L),
            "media" to mutableListOf<Any?>(),
            "iceOptions" to "ice2",
        )
        if (iceParameters["iceLite"] == true) sdp["icelite"] = "ice-lite"
        sdp["fingerprint"] = json("type" to fp["algorithm"], "hash" to fp["value"])
        sdp["groups"] = mutableListOf<Any?>(json("type" to "BUNDLE", "mids" to ""))
    }

    fun updateIceParameters(p: Json) {
        ice = p
        if (p["iceLite"] == true) sdp["icelite"] = "ice-lite" else sdp.remove("icelite")
        for (s in sections) s.setIceParameters(p)
    }

    fun updateDtlsRole(role: String) {
        dtls["role"] = role
        for (s in sections) s.setDtlsRole(role)
    }

    fun setSessionExtmapAllowMixed() { sdp["extmapAllowMixed"] = "extmap-allow-mixed" }

    /** Where the next sent track goes: in a closed section's place if there is one. */
    fun nextMediaSectionIdx(): Pair<Int, String?> {
        for (i in sections.indices) if (sections[i].closed) return i to sections[i].mid
        return sections.size to null
    }

    fun send(offerMediaObject: Json, reuseMid: String?, offerRtpParameters: Json, answerRtpParameters: Json, codecOptions: Json?) {
        val s = answerMediaSection(ice, iceCandidates, dtls, offerMediaObject, offerRtpParameters, answerRtpParameters, codecOptions)
        val o = s.obj
        // The dependency descriptor only works with codecs that carry it. This server offers neither, so it is never advertised.
        val hasDd = o["rtp"].objs().any { (it["codec"] as String).lowercase() in setOf("av1", "h264") }
        if (!hasDd) o["ext"] = o["ext"].objs().filter { it["uri"] != "https://aomediacodec.github.io/av1-rtp-spec/#dependency-descriptor-rtp-header-extension" }.toMutableList<Any?>()
        when {
            reuseMid != null -> replace(s, reuseMid)
            !midToIndex.containsKey(s.mid) -> add(s)
            else -> replace(s, null)
        }
    }

    fun receive(mid: String, kind: String, offerRtpParameters: Json, streamId: String, trackId: String) {
        setSessionExtmapAllowMixed()
        val s = offerMediaSection(ice, iceCandidates, dtls, mid, kind, offerRtpParameters, streamId, trackId)
        val old = sections.firstOrNull { it.closed }
        if (old != null) replace(s, old.mid) else add(s)
    }

    private fun find(mid: String): MediaSection = sections[midToIndex[mid] ?: throw IllegalStateException("no media section found with mid '$mid'")]

    fun pauseMediaSection(mid: String) = find(mid).pause()
    fun resumeSendingMediaSection(mid: String) { find(mid).obj["direction"] = "recvonly" }
    fun resumeReceivingMediaSection(mid: String) { find(mid).obj["direction"] = "sendonly" }

    /** Closes a section. The first one cannot go (the bundle hangs on it), so it is disabled instead: false then. */
    fun closeMediaSection(mid: String): Boolean {
        val s = find(mid)
        if (mid == firstMid) {
            s.disable()
            return false
        }
        s.close()
        bundleMids()
        return true
    }

    fun getSdp(): String {
        val origin = sdp["origin"].obj()
        origin["sessionVersion"] = (origin["sessionVersion"] as Long) + 1
        return writeSdp(sdp)
    }

    private fun add(s: MediaSection) {
        if (firstMid == null) firstMid = s.mid
        sections += s
        midToIndex[s.mid] = sections.size - 1
        sdp.list("media").add(s.obj)
        bundleMids()
    }

    private fun replace(s: MediaSection, reuseMid: String?) {
        if (reuseMid != null) {
            val i = midToIndex[reuseMid] ?: throw IllegalStateException("no media section found for reuseMid '$reuseMid'")
            val old = sections[i]
            sections[i] = s
            midToIndex.remove(old.mid)
            midToIndex[s.mid] = i
            sdp.list("media")[i] = s.obj
            bundleMids()
        } else {
            val i = midToIndex[s.mid] ?: throw IllegalStateException("no media section found with mid '${s.mid}'")
            sections[i] = s
            sdp.list("media")[i] = s.obj
        }
    }

    private fun bundleMids() {
        sdp["groups"].objs()[0]["mids"] = sections.filter { !it.closed }.joinToString(" ") { it.mid }
    }
}
