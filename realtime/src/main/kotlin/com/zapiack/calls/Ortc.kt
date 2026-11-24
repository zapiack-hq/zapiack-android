package com.zapiack.calls

/*
 * What the phone can send and receive, matched against what the server's router offers, and the RTP parameters that follow from it.
 * This is the part of the mediasoup protocol that decides which codec and which header extensions two sides will speak, so it follows
 * the JavaScript client exactly. Everything is plain JSON-shaped maps, the same ones the server sends and takes back.
 *
 * Differences, on purpose: no simulcast or SVC (a call sends one encoding), no data channels, and H.264 is matched on its profile and
 * packetisation mode only. The server offers Opus and VP8.
 *
 * Derived from mediasoup-client (ISC licence, Copyright (c) 2015, Iñaki Baz Castillo and contributors).
 */

private val MIME = Regex("^(audio|video)/(.+)", RegexOption.IGNORE_CASE)

/** Fills in what the capabilities leave out, as the JavaScript client does, and throws on what cannot be used. */
internal fun validateAndNormalizeRtpCapabilities(caps: Json) {
    caps.list("codecs")
    for (c in caps["codecs"].objs()) {
        val m = MIME.find((c["mimeType"] as? String) ?: "") ?: throw IllegalArgumentException("invalid codec.mimeType")
        c["kind"] = m.groupValues[1].lowercase()
        if (c["preferredPayloadType"] !is Number) throw IllegalArgumentException("missing codec.preferredPayloadType")
        if (c["clockRate"] !is Number) throw IllegalArgumentException("missing codec.clockRate")
        normalizeCodec(c, c["kind"] == "audio")
    }
    caps.list("headerExtensions")
    for (e in caps["headerExtensions"].objs()) {
        if (e["kind"] != "audio" && e["kind"] != "video") throw IllegalArgumentException("invalid ext.kind")
        if (falsy(e["uri"])) throw IllegalArgumentException("missing ext.uri")
        if (e["preferredId"] !is Number) throw IllegalArgumentException("missing ext.preferredId")
        if (falsy(e["preferredEncrypt"])) e["preferredEncrypt"] = false
        if (falsy(e["direction"])) e["direction"] = "sendrecv"
    }
}

private fun normalizeCodec(c: Json, audio: Boolean) {
    if (audio) {
        if (c["channels"] !is Number) c["channels"] = 1L
    } else {
        c.remove("channels")
    }
    if (c["parameters"] !is Map<*, *>) c["parameters"] = LinkedHashMap<String, Any?>()
    val p = c["parameters"].obj()
    for (k in p.keys.toList()) {
        if (p[k] == null) p[k] = ""
        if (p[k] !is String && p[k] !is Number) throw IllegalArgumentException("invalid codec parameter [key:$k]")
        if (k == "apt" && p[k] !is Number) throw IllegalArgumentException("invalid codec apt parameter")
    }
    if (c["rtcpFeedback"] !is List<*>) c["rtcpFeedback"] = mutableListOf<Any?>()
    for (fb in c["rtcpFeedback"].objs()) {
        if (falsy(fb["type"])) throw IllegalArgumentException("missing fb.type")
        if (fb["parameter"] !is String || fb["parameter"] == "") fb["parameter"] = ""
    }
}

/** A codec in RTP parameters (payloadType, not preferredPayloadType): the same fill-in. */
internal fun validateAndNormalizeRtpParameters(params: Json) {
    if (params["codecs"] !is List<*>) throw IllegalArgumentException("missing params.codecs")
    for (c in params["codecs"].objs()) {
        val m = MIME.find((c["mimeType"] as? String) ?: "") ?: throw IllegalArgumentException("invalid codec.mimeType")
        if (c["payloadType"] !is Number) throw IllegalArgumentException("missing codec.payloadType")
        if (c["clockRate"] !is Number) throw IllegalArgumentException("missing codec.clockRate")
        normalizeCodec(c, m.groupValues[1].lowercase() == "audio")
    }
    params.list("headerExtensions")
    for (e in params["headerExtensions"].objs()) {
        if (falsy(e["uri"])) throw IllegalArgumentException("missing ext.uri")
        if (e["id"] !is Number) throw IllegalArgumentException("missing ext.id")
        if (falsy(e["encrypt"])) e["encrypt"] = false
        if (e["parameters"] !is Map<*, *>) e["parameters"] = LinkedHashMap<String, Any?>()
    }
    params.list("encodings")
    for (e in params["encodings"].objs()) if (e["dtx"] != true) e["dtx"] = false
    if (params["rtcp"] !is Map<*, *>) params["rtcp"] = LinkedHashMap<String, Any?>()
    val rtcp = params["rtcp"].obj()
    if (rtcp["reducedSize"] != true) rtcp["reducedSize"] = true
}

internal fun isRtxCodec(c: Json?): Boolean = c != null && Regex(".+/rtx$", RegexOption.IGNORE_CASE).containsMatchIn(c["mimeType"] as String)

private fun level(profileLevelId: Any?): Int {
    val s = profileLevelId?.toString() ?: ""
    return if (s.length == 6) s.substring(4).toIntOrNull(16) ?: 0 else 0
}

private fun matchCodecs(a: Json, b: Json, strict: Boolean = false, modify: Boolean = false): Boolean {
    val am = (a["mimeType"] as String).lowercase()
    if (am != (b["mimeType"] as String).lowercase()) return false
    if (a["clockRate"] != b["clockRate"]) return false
    if (a["channels"] != b["channels"]) return false
    val ap = (a["parameters"] as? Map<*, *>)?.obj() ?: LinkedHashMap()
    val bp = (b["parameters"] as? Map<*, *>)?.obj() ?: LinkedHashMap()
    when (am) {
        "video/h264" -> if (strict) {
            if ((ap["packetization-mode"] ?: 0L) != (bp["packetization-mode"] ?: 0L)) return false
            val pa = ap["profile-level-id"]?.toString()
            val pb = bp["profile-level-id"]?.toString()
            if (pa != null && pb != null) {
                if (pa.length != 6 || pb.length != 6 || !pa.substring(0, 4).equals(pb.substring(0, 4), ignoreCase = true)) return false
                if (modify) {
                    val lvl = minOf(level(pa), level(pb)).toString(16).padStart(2, '0')
                    val id = pa.substring(0, 4) + lvl
                    ap["profile-level-id"] = id
                    bp["profile-level-id"] = id
                }
            }
        }
        "video/vp9" -> if (strict && (ap["profile-id"] ?: 0L) != (bp["profile-id"] ?: 0L)) return false
    }
    return true
}

private fun matchHeaderExtensions(a: Json, b: Json): Boolean {
    if (a["kind"] != null && b["kind"] != null && a["kind"] != b["kind"]) return false
    return a["uri"] == b["uri"]
}

private fun reduceRtcpFeedback(a: Json, b: Json): MutableList<Any?> {
    val out = mutableListOf<Any?>()
    for (afb in a["rtcpFeedback"].objs()) {
        val match = b["rtcpFeedback"].objs().firstOrNull { bfb -> bfb["type"] == afb["type"] && (bfb["parameter"] == afb["parameter"] || (falsy(bfb["parameter"]) && falsy(afb["parameter"]))) }
        if (match != null) out += match
    }
    return out
}

/**
 * The codecs and header extensions both sides share, with the payload types each side uses for them. [local] is this phone, [remote] is
 * the router; the router's order of preference wins.
 */
internal fun getExtendedRtpCapabilities(local: Json, remote: Json, preferLocalCodecsOrder: Boolean = false): Json {
    val codecs = mutableListOf<Any?>()
    val headerExtensions = mutableListOf<Any?>()
    val localCodecs = local["codecs"].objs()
    val remoteCodecs = remote["codecs"].objs()

    fun extended(l: Json, r: Json): Json = jsonNoNulls(
        "kind" to l["kind"],
        "mimeType" to l["mimeType"],
        "clockRate" to l["clockRate"],
        "channels" to l["channels"],
        "localPayloadType" to l["preferredPayloadType"],
        "remotePayloadType" to r["preferredPayloadType"],
        "localParameters" to (l["parameters"] ?: LinkedHashMap<String, Any?>()),
        "remoteParameters" to (r["parameters"] ?: LinkedHashMap<String, Any?>()),
        "rtcpFeedback" to reduceRtcpFeedback(l, r),
    )

    if (preferLocalCodecsOrder) {
        for (l in localCodecs) {
            if (isRtxCodec(l)) continue
            val r = remoteCodecs.firstOrNull { matchCodecs(it, l, strict = true, modify = true) } ?: continue
            codecs += extended(l, r)
        }
    } else {
        for (r in remoteCodecs) {
            if (isRtxCodec(r)) continue
            val l = localCodecs.firstOrNull { matchCodecs(it, r, strict = true, modify = true) } ?: continue
            codecs += extended(l, r)
        }
    }

    for (c in codecs.objs()) {
        val lrtx = localCodecs.firstOrNull { isRtxCodec(it) && it["parameters"].obj()["apt"] == c["localPayloadType"] }
        val rrtx = remoteCodecs.firstOrNull { isRtxCodec(it) && it["parameters"].obj()["apt"] == c["remotePayloadType"] }
        if (lrtx != null && rrtx != null) {
            c["localRtxPayloadType"] = lrtx["preferredPayloadType"]
            c["remoteRtxPayloadType"] = rrtx["preferredPayloadType"]
        }
    }

    for (re in remote["headerExtensions"].objs()) {
        val le = local["headerExtensions"].objs().firstOrNull { matchHeaderExtensions(it, re) } ?: continue
        val direction = when (re["direction"]) {
            "sendrecv" -> "sendrecv"
            "recvonly" -> "sendonly"
            "sendonly" -> "recvonly"
            "inactive" -> "inactive"
            else -> "sendrecv"
        }
        headerExtensions += jsonNoNulls(
            "kind" to re["kind"],
            "uri" to re["uri"],
            "sendId" to le["preferredId"],
            "recvId" to re["preferredId"],
            "encrypt" to (le["preferredEncrypt"] ?: false),
            "direction" to direction,
        )
    }
    return json("codecs" to codecs, "headerExtensions" to headerExtensions)
}

/** What this phone tells the server it can receive: the shared codecs, under the payload types the router uses. */
internal fun getRecvRtpCapabilities(ext: Json): Json {
    val codecs = mutableListOf<Any?>()
    val headerExtensions = mutableListOf<Any?>()
    for (c in ext["codecs"].objs()) {
        codecs += jsonNoNulls(
            "kind" to c["kind"],
            "mimeType" to c["mimeType"],
            "preferredPayloadType" to c["remotePayloadType"],
            "clockRate" to c["clockRate"],
            "channels" to c["channels"],
            "parameters" to c["localParameters"],
            "rtcpFeedback" to c["rtcpFeedback"],
        )
        if (falsy(c["remoteRtxPayloadType"])) continue
        codecs += json(
            "kind" to c["kind"],
            "mimeType" to "${c["kind"]}/rtx",
            "preferredPayloadType" to c["remoteRtxPayloadType"],
            "clockRate" to c["clockRate"],
            "parameters" to json("apt" to c["remotePayloadType"]),
            "rtcpFeedback" to mutableListOf<Any?>(),
        )
    }
    for (e in ext["headerExtensions"].objs()) {
        if (e["direction"] != "sendrecv" && e["direction"] != "recvonly") continue
        headerExtensions += json("kind" to e["kind"], "uri" to e["uri"], "preferredId" to e["recvId"], "preferredEncrypt" to (e["encrypt"] ?: false), "direction" to e["direction"])
    }
    return json("codecs" to codecs, "headerExtensions" to headerExtensions)
}

private fun rtxOf(c: Json): Json = json(
    "mimeType" to "${c["kind"]}/rtx",
    "payloadType" to c["localRtxPayloadType"],
    "clockRate" to c["clockRate"],
    "parameters" to json("apt" to c["localPayloadType"]),
    "rtcpFeedback" to mutableListOf<Any?>(),
)

private fun emptyParams(): Json = json("codecs" to mutableListOf<Any?>(), "headerExtensions" to mutableListOf<Any?>(), "encodings" to mutableListOf<Any?>(), "rtcp" to json())

/** The RTP parameters of a track this phone sends. `mid`, `encodings` and `rtcp` are filled in once the offer exists. */
internal fun getSendingRtpParameters(kind: String, ext: Json): Json {
    val params = emptyParams()
    for (c in ext["codecs"].objs()) {
        if (c["kind"] != kind) continue
        params.list("codecs").add(jsonNoNulls("mimeType" to c["mimeType"], "payloadType" to c["localPayloadType"], "clockRate" to c["clockRate"], "channels" to c["channels"], "parameters" to c["localParameters"], "rtcpFeedback" to c["rtcpFeedback"]))
        if (!falsy(c["localRtxPayloadType"])) params.list("codecs").add(rtxOf(c))
    }
    for (e in ext["headerExtensions"].objs()) {
        if ((e["kind"] != null && e["kind"] != kind) || (e["direction"] != "sendrecv" && e["direction"] != "sendonly")) continue
        params.list("headerExtensions").add(json("uri" to e["uri"], "id" to e["sendId"], "encrypt" to e["encrypt"], "parameters" to json()))
    }
    return params
}

/** The same, as they belong in the answer the phone is told: the router's own parameters, with feedback reduced to what it supports. */
internal fun getSendingRemoteRtpParameters(kind: String, ext: Json): Json {
    val params = emptyParams()
    for (c in ext["codecs"].objs()) {
        if (c["kind"] != kind) continue
        params.list("codecs").add(jsonNoNulls("mimeType" to c["mimeType"], "payloadType" to c["localPayloadType"], "clockRate" to c["clockRate"], "channels" to c["channels"], "parameters" to c["remoteParameters"], "rtcpFeedback" to c["rtcpFeedback"]))
        if (!falsy(c["localRtxPayloadType"])) params.list("codecs").add(rtxOf(c))
    }
    for (e in ext["headerExtensions"].objs()) {
        if ((e["kind"] != null && e["kind"] != kind) || (e["direction"] != "sendrecv" && e["direction"] != "sendonly")) continue
        params.list("headerExtensions").add(json("uri" to e["uri"], "id" to e["sendId"], "encrypt" to e["encrypt"], "parameters" to json()))
    }
    val uris = params["headerExtensions"].objs().map { it["uri"] }.toSet()
    val drop = when {
        "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01" in uris -> setOf("goog-remb")
        "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time" in uris -> setOf("transport-cc")
        else -> setOf("transport-cc", "goog-remb")
    }
    for (c in params["codecs"].objs()) c["rtcpFeedback"] = c["rtcpFeedback"].objs().filter { it["type"] !in drop }.toMutableList<Any?>()
    return params
}

/** The first codec (and its RTX), or the one matching [wanted] when given. Needs codecs from [getSendingRtpParameters]. */
internal fun reduceCodecs(codecs: List<Json>, wanted: Json? = null): MutableList<Any?> {
    val out = mutableListOf<Any?>()
    if (wanted == null) {
        out += codecs[0]
        if (codecs.size > 1 && isRtxCodec(codecs[1])) out += codecs[1]
    } else {
        for (i in codecs.indices) {
            if (matchCodecs(codecs[i], wanted, strict = true)) {
                out += codecs[i]
                if (i + 1 < codecs.size && isRtxCodec(codecs[i + 1])) out += codecs[i + 1]
                break
            }
        }
        if (out.isEmpty()) throw IllegalArgumentException("no matching codec found")
    }
    return out
}

/** Opus works with NACK in libwebrtc without saying so. */
internal fun addNackSupportForOpus(caps: Json) {
    for (c in caps["codecs"].objs()) {
        val mt = (c["mimeType"] as String).lowercase()
        if ((mt == "audio/opus" || mt == "audio/multiopus") && c["rtcpFeedback"].objs().none { it["type"] == "nack" && falsy(it["parameter"]) }) {
            c.list("rtcpFeedback").add(json("type" to "nack"))
        }
    }
}

/** Lists a header extension the phone supports but does not announce, under the first free id. */
internal fun addHeaderExtensionSupport(caps: Json, ext: Json) {
    val list = caps.list("headerExtensions")
    if (list.objs().any { it["kind"] == ext["kind"] && it["uri"] == ext["uri"] }) return
    val taken = list.objs().filter { it["uri"] != ext["uri"] }.map { it["preferredId"] }.toSet()
    var id = 1L
    while (id in taken) id++
    list += json("kind" to ext["kind"], "uri" to ext["uri"], "preferredId" to id, "preferredEncrypt" to false, "direction" to ext["direction"])
}

/** `msid` is "<streamId> <trackId>". */
internal fun msidParts(msid: String?): Pair<String?, String?> {
    if (msid.isNullOrEmpty()) return null to null
    val parts = msid.trim().split(Regex("\\s+"))
    if (parts.isEmpty() || parts[0].isEmpty()) return null to null
    return parts[0] to parts.getOrNull(1)
}

/** Whether this phone can receive what the server describes: its first codec is one the phone said it can take. */
internal fun canReceive(rtpParameters: Json, rtpCapabilities: Json): Boolean {
    validateAndNormalizeRtpParameters(rtpParameters)
    val codecs = rtpParameters["codecs"].objs()
    if (codecs.isEmpty()) return false
    return rtpCapabilities["codecs"].objs().any { it["preferredPayloadType"] == codecs[0]["payloadType"] }
}
