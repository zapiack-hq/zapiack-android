package com.zapiack.push.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** What every push carries so a tap can be reported and followed. */
data class PushBlock(
    val messageId: String,
    val deliveryId: String,
    val link: String? = null,
    val image: String? = null,
    val event: String? = null,
    val windowMinutes: Int? = null,
    val screens: Boolean? = null,
    val actions: List<Action> = emptyList(),
    val style: Style? = null,
) {
    /** How the notification looks. Every part is checked here, so what reaches the drawing code is always safe to use. */
    data class Style(val color: String? = null, val thumbnail: String? = null, val shine: Boolean = false)

    data class Action(val id: String, val title: String, val event: String? = null, val link: String? = null)

    companion object {
        /** Reads the block from a push's data, or returns null when the push is not from Zapiack. */
        fun fromData(data: Map<String, String?>): PushBlock? {
            val m = data["zapiack_m"]
            val d = data["zapiack_d"]
            if (m.isNullOrEmpty() || d.isNullOrEmpty()) return null

            val tracking = data["zapiack_t"]?.let { runCatching { JSONObject(it) }.getOrNull() }
            val actions = data["zapiack_a"]?.let { raw ->
                runCatching {
                    val array = JSONArray(raw)
                    (0 until array.length()).mapNotNull { i ->
                        val a = array.optJSONObject(i) ?: return@mapNotNull null
                        val id = a.optString("id")
                        val title = a.optString("title")
                        if (id.isEmpty() || title.isEmpty()) return@mapNotNull null
                        Action(id, title, a.optStringOrNull("event"), a.optStringOrNull("link"))
                    }
                }.getOrNull()
            } ?: emptyList()

            val style = data["zapiack_s"]?.let { raw ->
                runCatching {
                    val o = JSONObject(raw)
                    val color = o.optStringOrNull("c")?.takeIf { Regex("^#[0-9A-Fa-f]{6}$").matches(it) }
                    val thumbnail = o.optStringOrNull("t")?.takeIf { it.startsWith("https://") }
                    val shine = o.optStringOrNull("e") == "shine"
                    if (color == null && thumbnail == null && !shine) null else Style(color, thumbnail, shine)
                }.getOrNull()
            }

            return PushBlock(
                messageId = m,
                deliveryId = d,
                link = data["zapiack_l"]?.takeIf { it.isNotEmpty() },
                image = data["zapiack_i"]?.takeIf { it.isNotEmpty() },
                event = data["zapiack_e"]?.takeIf { it.isNotEmpty() },
                windowMinutes = tracking?.takeIf { it.has("w") }?.optInt("w"),
                screens = tracking?.takeIf { it.has("s") }?.optBoolean("s"),
                actions = actions,
                style = style,
            )
        }
    }
}

internal fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

enum class EventType(val wire: String) { Clicked("clicked"), Action("action"), Screen("screen"), Custom("custom"), Received("received") }

data class SdkEvent(
    val type: EventType,
    val deliveryId: String,
    val sessionId: String? = null,
    val name: String? = null,
    val actionId: String? = null,
    val properties: Map<String, Any?>? = null,
    val previousScreen: String? = null,
    val at: Instant = Instant.now(),
    val clientEventId: String = UUID.randomUUID().toString(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("clientEventId", clientEventId)
        put("type", type.wire)
        put("deliveryId", deliveryId)
        sessionId?.let { put("sessionId", it) }
        name?.let { put("name", it) }
        actionId?.let { put("actionId", it) }
        properties?.let { put("properties", JSONObject(it)) }
        previousScreen?.let { put("previousScreen", it) }
        put("at", at.toString())
    }

    companion object {
        fun fromJson(o: JSONObject): SdkEvent = SdkEvent(
            type = EventType.entries.first { it.wire == o.getString("type") },
            deliveryId = o.getString("deliveryId"),
            sessionId = o.optStringOrNull("sessionId"),
            name = o.optStringOrNull("name"),
            actionId = o.optStringOrNull("actionId"),
            properties = o.optJSONObject("properties")?.toPlainMap(),
            previousScreen = o.optStringOrNull("previousScreen"),
            at = Instant.parse(o.getString("at")),
            clientEventId = o.getString("clientEventId"),
        )
    }
}

internal fun JSONObject.toPlainMap(): Map<String, Any?> = keys().asSequence().associateWith { k -> get(k).takeUnless { it == JSONObject.NULL } }

/** The window after a tap in which screens and events are credited to the push. */
data class ActiveSession(
    val sessionId: String,
    val deliveryId: String,
    val messageId: String,
    val startedAt: Instant,
    val windowMinutes: Int,
    val screens: Boolean,
) {
    fun isOpen(now: Instant): Boolean = !now.isAfter(startedAt.plusSeconds(windowMinutes * 60L))

    fun toJson(): String = JSONObject()
        .put("sessionId", sessionId).put("deliveryId", deliveryId).put("messageId", messageId)
        .put("startedAt", startedAt.toString()).put("windowMinutes", windowMinutes).put("screens", screens)
        .toString()

    companion object {
        fun fromJson(raw: String): ActiveSession? = runCatching {
            val o = JSONObject(raw)
            ActiveSession(o.getString("sessionId"), o.getString("deliveryId"), o.getString("messageId"), Instant.parse(o.getString("startedAt")), o.getInt("windowMinutes"), o.getBoolean("screens"))
        }.getOrNull()
    }
}

/** What was tapped, handed to the app so it can route. */
data class Tap(
    val messageId: String,
    val deliveryId: String,
    val sessionId: String,
    /** The button that was tapped, when it was a button. */
    val actionId: String?,
    /** The event name set on the notification or the button. */
    val event: String?,
    val link: String?,
)
