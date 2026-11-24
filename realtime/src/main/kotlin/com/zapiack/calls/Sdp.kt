package com.zapiack.calls

/*
 * The SDP the mediasoup protocol needs, read and written the way `sdp-transform` does: a session is a map, each media section is a map,
 * and the keys are the same ones (`rtp`, `fmtp`, `rtcpFb`, `ext`, `ssrcs`, ...). That keeps this port line for line with the JavaScript
 * client it must agree with, which the golden tests check byte for byte.
 *
 * Only what an audio and video call uses is understood. Anything else in a local SDP is ignored.
 *
 * Derived from mediasoup-client (ISC licence, Copyright (c) 2015, Iñaki Baz Castillo and contributors) and sdp-transform (MIT).
 */

/** A number when it reads back the same, as `sdp-transform` does: "111" is 111, "007" stays a string. */
internal fun toIntIfInt(v: String): Any {
    val n = v.toLongOrNull()
    if (n != null && n.toString() == v) return n
    val d = v.toDoubleOrNull()
    if (d != null && d % 1.0 != 0.0 && d.toString() == v) return d
    return v
}

/** `a=fmtp` configuration, `key=value;key=value`, as a map. Numbers stay numbers. */
internal fun parseParams(str: String): Json {
    val out = LinkedHashMap<String, Any?>()
    for (expr in str.split(Regex(";\\s?"))) {
        val eq = expr.indexOf('=')
        if (eq >= 0 && eq < expr.length - 1) out[expr.substring(0, eq)] = toIntIfInt(expr.substring(eq + 1))
        else if (expr.length > 1) out[expr] = null
    }
    return out
}

private val RTPMAP = Regex("^rtpmap:(\\d+) ([\\w\\-.]*)(?:\\s*/(\\d*)(?:\\s*/(\\S*))?)?")
private val FMTP = Regex("^fmtp:(\\d+) (.*)$")
private val RTCP_FB = Regex("^rtcp-fb:(\\*|\\d+) ([\\w\\-_]*)(?: ([\\w\\-_]*))?")
private val EXTMAP = Regex("^extmap:(\\d+)(?:/(\\w+))?(?: (urn:ietf:params:rtp-hdrext:encrypt))? (\\S*)(?: (\\S*))?")
private val SSRC = Regex("^ssrc:(\\d+) ([\\w_\\-]*)(?::(.*))?")
private val SSRC_GROUP = Regex("^ssrc-group:([\\x21\\x23\\x24\\x25\\x26\\x27\\x2A\\x2B\\x2D\\x2E\\w]*) (.*)")
private val FINGERPRINT = Regex("^fingerprint:(\\S*) (\\S*)")
private val GROUP = Regex("^group:(\\S*)(?: (.*))?")
private val MSID = Regex("^msid:(\\S*) ?(\\S*)?")
private val DIRECTIONS = setOf("sendrecv", "sendonly", "recvonly", "inactive")

/** Reads an SDP into the shape `sdp-transform` gives: session keys at the top, a `media` list below. */
internal fun parseSdp(sdp: String): Json {
    val session: Json = json("media" to mutableListOf<Any?>())
    var at: Json = session
    for (raw in sdp.split(Regex("\r?\n"))) {
        if (raw.length < 3 || raw[1] != '=') continue
        val type = raw[0]
        val content = raw.substring(2)
        if (type == 'm') {
            val parts = content.split(' ')
            at = json(
                "type" to parts[0],
                "port" to (parts.getOrNull(1)?.toLongOrNull() ?: 0L),
                "protocol" to (parts.getOrNull(2) ?: ""),
                "payloads" to (if (parts.size > 3) parts.drop(3).joinToString(" ") else ""),
            )
            session.list("media").add(at)
            continue
        }
        if (type == 'c') {
            Regex("^IN IP(\\d) (\\S*)").find(content)?.let { at["connection"] = json("version" to it.groupValues[1].toLong(), "ip" to it.groupValues[2]) }
            continue
        }
        if (type != 'a') continue

        var m: MatchResult?
        m = RTPMAP.find(content)
        if (m != null) {
            at.list("rtp").add(
                jsonNoNulls(
                    "payload" to m.groupValues[1].toLong(),
                    "codec" to m.groupValues[2],
                    "rate" to m.groups[3]?.value?.let { toIntIfInt(it) },
                    "encoding" to m.groups[4]?.value?.let { toIntIfInt(it) },
                ),
            )
            continue
        }
        m = FMTP.find(content)
        if (m != null) {
            at.list("fmtp").add(json("payload" to m.groupValues[1].toLong(), "config" to m.groupValues[2]))
            continue
        }
        m = RTCP_FB.find(content)
        if (m != null) {
            at.list("rtcpFb").add(
                jsonNoNulls(
                    "payload" to (if (m.groupValues[1] == "*") "*" else m.groupValues[1].toLong()),
                    "type" to m.groupValues[2],
                    "subtype" to m.groups[3]?.value,
                ),
            )
            continue
        }
        m = EXTMAP.find(content)
        if (m != null) {
            at.list("ext").add(
                jsonNoNulls(
                    "value" to m.groupValues[1].toLong(),
                    "direction" to m.groups[2]?.value,
                    "encrypt-uri" to m.groups[3]?.value,
                    "uri" to m.groupValues[4],
                    "config" to m.groups[5]?.value,
                ),
            )
            continue
        }
        when {
            content == "extmap-allow-mixed" -> at["extmapAllowMixed"] = "extmap-allow-mixed"
            content.startsWith("setup:") -> at["setup"] = content.substring(6)
            content.startsWith("mid:") -> at["mid"] = content.substring(4)
            content in DIRECTIONS -> at["direction"] = content
            content.startsWith("ice-ufrag:") -> at["iceUfrag"] = content.substring(10)
            content.startsWith("ice-pwd:") -> at["icePwd"] = content.substring(8)
            else -> {
                val msid = MSID.find(content)
                if (msid != null) { at.list("msid").add(json("id" to msid.groupValues[1], "appdata" to msid.groups[2]?.value)); continue }
                val fp = FINGERPRINT.find(content)
                if (fp != null) { at["fingerprint"] = json("type" to fp.groupValues[1], "hash" to fp.groupValues[2]); continue }
                val sg = SSRC_GROUP.find(content)
                if (sg != null) { at.list("ssrcGroups").add(json("semantics" to sg.groupValues[1], "ssrcs" to sg.groupValues[2])); continue }
                val ss = SSRC.find(content)
                if (ss != null) { at.list("ssrcs").add(jsonNoNulls("id" to ss.groupValues[1].toLong(), "attribute" to ss.groupValues[2], "value" to ss.groups[3]?.value)); continue }
                val gr = GROUP.find(content)
                if (gr != null) at.list("groups").add(json("type" to gr.groupValues[1], "mids" to (gr.groups[2]?.value ?: "")))
            }
        }
    }
    return session
}

/** Writes the session the way `sdp-transform` does, attribute for attribute in its order, with CRLF line ends. */
internal fun writeSdp(s: Json): String {
    val out = ArrayList<String>()
    out += "v=${s["version"] ?: 0}"
    val o = s["origin"].obj()
    out += "o=${o["username"]} ${o["sessionId"]} ${o["sessionVersion"]} ${o["netType"]} IP${o["ipVer"]} ${o["address"]}"
    out += "s=${s["name"]}"
    val t = s["timing"].obj()
    out += "t=${t["start"]} ${t["stop"]}"
    attributes(out, s)
    for (m in s["media"].objs()) {
        out += "m=${m["type"]} ${m["port"]} ${m["protocol"]} ${m["payloads"]}"
        m["connection"]?.obj()?.let { out += "c=IN IP${it["version"]} ${it["ip"]}" }
        attributes(out, m)
    }
    return out.joinToString("\r\n") + "\r\n"
}

private fun attributes(out: MutableList<String>, m: Json) {
    for (r in m["rtp"].objs()) out += "a=rtpmap:${r["payload"]} ${r["codec"]}/${r["rate"]}${r["encoding"]?.let { "/$it" } ?: ""}"
    for (f in m["fmtp"].objs()) out += "a=fmtp:${f["payload"]} ${f["config"]}"
    // A feedback with an empty parameter keeps its trailing space, as the JavaScript writer leaves it.
    for (f in m["rtcpFb"].objs()) out += if (f["subtype"] != null) "a=rtcp-fb:${f["payload"]} ${f["type"]} ${f["subtype"]}" else "a=rtcp-fb:${f["payload"]} ${f["type"]}"
    for (e in m["ext"].objs()) out += "a=extmap:${e["value"]}${e["direction"]?.let { "/$it" } ?: ""}${e["encrypt-uri"]?.let { " $it" } ?: ""} ${e["uri"]}${e["config"]?.let { " $it" } ?: ""}"
    if (m["extmapAllowMixed"] != null) out += "a=extmap-allow-mixed"
    m["setup"]?.let { out += "a=setup:$it" }
    m["mid"]?.let { out += "a=mid:$it" }
    for (x in m["msid"].objs()) out += "a=msid:${x["id"]}${x["appdata"]?.let { " $it" } ?: ""}"
    m["direction"]?.let { out += "a=$it" }
    if (m["icelite"] != null) out += "a=ice-lite"
    m["iceUfrag"]?.let { out += "a=ice-ufrag:$it" }
    m["icePwd"]?.let { out += "a=ice-pwd:$it" }
    m["fingerprint"]?.obj()?.let { out += "a=fingerprint:${it["type"]} ${it["hash"]}" }
    for (c in m["candidates"].objs()) out += "a=candidate:${c["foundation"]} ${c["component"]} ${c["transport"]} ${c["priority"]} ${c["ip"]} ${c["port"]} typ ${c["type"]}${c["tcptype"]?.let { " tcptype $it" } ?: ""}"
    if (m["endOfCandidates"] != null) out += "a=end-of-candidates"
    m["iceOptions"]?.let { out += "a=ice-options:$it" }
    for (x in m["ssrcs"].objs()) out += "a=ssrc:${x["id"]} ${x["attribute"]}${x["value"]?.let { ":$it" } ?: ""}"
    for (x in m["ssrcGroups"].objs()) out += "a=ssrc-group:${x["semantics"]} ${x["ssrcs"]}"
    for (g in m["groups"].objs()) out += "a=group:${g["type"]} ${g["mids"]}"
    if (m["rtcpMux"] != null) out += "a=rtcp-mux"
    if (m["rtcpRsize"] != null) out += "a=rtcp-rsize"
}
