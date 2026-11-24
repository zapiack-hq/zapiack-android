package com.zapiack.push.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * The live part of a template push, sent by Zapiack in the `zp_live` data key: timers, progress, counters, dates and badges. The
 * app draws them in the notification; a timer keeps counting down on its own, without another push.
 *
 * Everything is checked as it is read, so the drawing code only ever sees values it can use. Anything it cannot read is left out.
 */
sealed class Live {
    data class Timer(
        val mode: String,
        val source: String,
        val format: String,
        val look: String,
        val tick: Boolean,
        val color: String?,
        val label: String,
        val onEnd: String,
        val expiredText: String,
    ) : Live() {
        /**
         * When it ends, in milliseconds since 1970. A fixed timer counts to its date; per-user and evergreen ones count their minutes
         * from when the push arrived. Null when the source cannot be read.
         */
        fun endsAt(receivedAtMillis: Long): Long? = when (mode) {
            "fixed" -> parseIsoMillis(source)
            else -> source.toDoubleOrNull()?.takeIf { it > 0 }?.let { receivedAtMillis + (it * 60_000).toLong() }
        }
    }

    data class Progress(val steps: List<String>, val current: Int, val color: String?) : Live()
    data class Counter(val value: String, val label: String, val color: String?) : Live()
    data class DateText(val value: String, val style: String) : Live()
    data class Badge(val text: String, val color: String?) : Live()

    companion object {
        const val DATA_KEY = "zp_live"
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

        private fun JSONObject.color(key: String) = optStringOrNull(key)?.takeIf { HEX.matches(it) }

        /** Reads the live elements from a push's data. An absent or broken value gives an empty list, never an error. */
        fun fromData(data: Map<String, String?>): List<Live> {
            val raw = data[DATA_KEY] ?: return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { read(it) } }
        }

        private fun read(o: JSONObject): Live? {
            return when (o.optString("type")) {
            "timer" -> Timer(
                mode = o.optString("mode").takeIf { it in setOf("fixed", "perUser", "evergreen") } ?: return null,
                source = o.optString("source"),
                format = o.optString("format").takeIf { it in setOf("hmmss", "mmss", "dhm") } ?: "hmmss",
                look = if (o.optString("look") == "pills") "pills" else "text",
                tick = o.optBoolean("tick", true),
                color = o.color("color"),
                label = o.optString("label"),
                onEnd = o.optString("onEnd").takeIf { it in setOf("expired", "hide", "keep") } ?: "keep",
                expiredText = o.optString("expiredText"),
            )
            "progress" -> {
                val steps = o.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.take(6) ?: return null
                if (steps.size < 2) null else Progress(steps, o.optInt("current", 0).coerceIn(0, steps.size), o.color("color"))
            }
            "counter" -> Counter(o.optString("value"), o.optString("label"), o.color("color"))
            "date" -> DateText(o.optString("value"), o.optString("style").ifEmpty { "long" })
            "badge" -> o.optString("text").takeIf { it.isNotBlank() }?.let { Badge(it, o.color("color")) }
            else -> null
            }
        }

        private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?(Z|[+-]\d{2}:?\d{2})$""")

        /**
         * An ISO 8601 date and time, as Zapiack sends it ("2026-11-06T15:23:27Z" or with an offset), in milliseconds since 1970. Read
         * by hand rather than with java.time, which older Android versions do not have. Null when it is not such a date.
         */
        fun parseIsoMillis(value: String): Long? {
            val m = ISO.matchEntire(value.trim()) ?: return null
            val (y, mo, d, h, mi) = m.destructured.toList().take(5).map { it.toInt() }
            val sec = m.groupValues[6].toIntOrNull() ?: 0
            val ms = m.groupValues[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
            if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || sec > 60) return null
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(y, mo - 1, d, h, mi, sec)
                set(Calendar.MILLISECOND, ms)
            }
            val zone = m.groupValues[8]
            val offsetMin = if (zone == "Z") 0 else {
                val digits = zone.drop(1).replace(":", "")
                val sign = if (zone[0] == '-') -1 else 1
                sign * (digits.take(2).toInt() * 60 + digits.drop(2).toInt())
            }
            return cal.timeInMillis - offsetMin * 60_000L
        }

        /**
         * What a timer reads with `remainingMs` left, as the notification shows it when it is not ticking: "3:23:27", "23:27", or
         * "2d 14h 9m" with units that are still zero at the front left out. At zero or below it reads all zeroes.
         */
        fun timerText(remainingMs: Long, format: String): String {
            val total = (remainingMs.coerceAtLeast(0) / 1000)
            val s = total % 60
            val m = (total / 60) % 60
            val h = total / 3600
            fun two(n: Long) = n.toString().padStart(2, '0')
            return when (format) {
                "mmss" -> "${two(total / 60)}:${two(s)}"
                "dhm" -> {
                    val parts = listOf(h / 24 to "d", h % 24 to "h", m to "m")
                    val first = parts.indexOfFirst { it.first > 0 }.let { if (it == -1) parts.lastIndex else it }
                    parts.drop(first).joinToString(" ") { "${it.first}${it.second}" }
                }
                else -> "$h:${two(m)}:${two(s)}"
            }
        }
    }
}
