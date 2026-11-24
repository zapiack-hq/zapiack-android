package com.zapiack.calls

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/*
 * The mediasoup protocol is JSON: what the server sends and what the client sends back are objects and lists, and the negotiation reads
 * and rewrites them in place. They are kept as plain maps and lists (the same shapes the web client uses), with every whole number a
 * Long so a payload type read from an SDP equals the same one read from the server.
 */

internal typealias Json = MutableMap<String, Any?>

internal fun json(vararg pairs: Pair<String, Any?>): Json = LinkedHashMap<String, Any?>().also { it.putAll(pairs) }

/** Like [json], leaving out what is null: JSON has no "undefined", and an absent key is how the protocol says it. */
internal fun jsonNoNulls(vararg pairs: Pair<String, Any?>): Json = LinkedHashMap<String, Any?>().also { m -> for ((k, v) in pairs) if (v != null) m[k] = v }

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Json = this as Json

@Suppress("UNCHECKED_CAST")
internal fun Any?.arr(): MutableList<Any?> = this as MutableList<Any?>

internal fun Any?.objs(): List<Json> = (this as? List<*>)?.map { it.obj() } ?: emptyList()

internal fun Json.list(key: String): MutableList<Any?> = getOrPut(key) { mutableListOf<Any?>() }.arr()

internal fun Any?.long(): Long? = (this as? Number)?.toLong()

internal fun Any?.str(): String? = this as? String

/** True for what JavaScript would call falsy: missing, empty, false or zero. */
internal fun falsy(v: Any?): Boolean = v == null || v == "" || v == false || (v is Number && v.toDouble() == 0.0)

/**
 * A copy of any JSON-like value (org.json objects and arrays, maps, lists), as maps, lists, strings, booleans and numbers where whole
 * numbers are Longs. The negotiation edits what it is given, so callers hand it copies.
 */
internal fun normalize(v: Any?): Any? = when (v) {
    null -> null
    JSONObject.NULL -> null
    is JSONObject -> LinkedHashMap<String, Any?>().also { m -> for (k in v.keys()) m[k] = normalize(v.get(k)) }
    is JSONArray -> MutableList(v.length()) { normalize(v.get(it)) }
    is Map<*, *> -> LinkedHashMap<String, Any?>().also { m -> for ((k, x) in v) m[k.toString()] = normalize(x) }
    is Iterable<*> -> v.mapTo(ArrayList()) { normalize(it) }
    is Int -> v.toLong()
    is Short -> v.toLong()
    is Byte -> v.toLong()
    is Float -> normalize(v.toDouble())
    is Double -> if (v % 1.0 == 0.0 && abs(v) < 9.0e15) v.toLong() else v
    else -> v
}

internal fun deepCopy(v: Json): Json = normalize(v).obj()

internal fun deepCopyList(v: List<Any?>): MutableList<Any?> = normalize(v).arr()
