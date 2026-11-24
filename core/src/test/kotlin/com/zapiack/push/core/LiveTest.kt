package com.zapiack.push.core

import java.time.Instant
import kotlin.test.*

class LiveTest {
    private val data = mapOf(
        "zapiack_m" to "msg_1",
        "zapiack_d" to "del_1",
        "zp_live" to """[
            {"type":"timer","mode":"fixed","source":"2026-11-06T15:23:27Z","format":"hmmss","look":"pills","tick":true,"color":"#E5484D","label":"to extend your 483 day streak","onEnd":"expired","expiredText":"Streak ended"},
            {"type":"progress","steps":["Packed","Shipped","Nearby","Delivered"],"current":2,"color":"#2BB673"},
            {"type":"counter","value":"12","label":"items","color":"not a colour"},
            {"type":"badge","text":"New","color":"#2BB673"},
            {"type":"nonsense"}
        ]""",
    )

    @Test
    fun `reads every element it knows and drops what it does not`() {
        val live = Live.fromData(data)
        assertEquals(4, live.size)
        val timer = live[0] as Live.Timer
        assertEquals("pills", timer.look)
        assertEquals("#E5484D", timer.color)
        assertEquals("expired", timer.onEnd)
        assertEquals(Live.Progress(listOf("Packed", "Shipped", "Nearby", "Delivered"), 2, "#2BB673"), live[1])
        // A colour that is not #RRGGBB is dropped rather than handed to the drawing code.
        assertNull((live[2] as Live.Counter).color)
    }

    @Test
    fun `broken or missing data is an empty list, never an error`() {
        assertTrue(Live.fromData(mapOf("zp_live" to "{oops")).isEmpty())
        assertTrue(Live.fromData(emptyMap()).isEmpty())
        assertTrue(Live.fromData(mapOf("zp_live" to """[{"type":"progress","steps":["only one"]}]""")).isEmpty())
    }

    @Test
    fun `a fixed timer ends at its date, a per-user one minutes after arrival`() {
        val arrived = Instant.parse("2026-11-06T12:00:00Z").toEpochMilli()
        val fixed = Live.fromData(data)[0] as Live.Timer
        assertEquals(Instant.parse("2026-11-06T15:23:27Z").toEpochMilli(), fixed.endsAt(arrived))
        val perUser = fixed.copy(mode = "perUser", source = "30")
        assertEquals(arrived + 30 * 60_000, perUser.endsAt(arrived))
        assertNull(fixed.copy(source = "tomorrow").endsAt(arrived))
        assertNull(perUser.copy(source = "-5").endsAt(arrived))
    }

    @Test
    fun `reads ISO dates the way the JVM does, without java time`() {
        for (v in listOf("2026-11-06T15:23:27Z", "2026-11-06T15:23:27.512Z", "2026-11-06T15:23:27+01:00", "2026-11-06T15:23-0530", "2028-02-29T00:00:00Z")) {
            assertEquals(java.time.OffsetDateTime.parse(v.replace("-0530", "-05:30").let { if (Regex("T\\d{2}:\\d{2}[+-]").containsMatchIn(it)) it.replace(Regex("(T\\d{2}:\\d{2})"), "$1:00") else it }).toInstant().toEpochMilli(), Live.parseIsoMillis(v), v)
        }
        assertNull(Live.parseIsoMillis("2026-13-06T15:23:27Z"))
        assertNull(Live.parseIsoMillis("tomorrow"))
    }

    @Test
    fun `timer text matches each format`() {
        val left = (3 * 3600 + 23 * 60 + 27) * 1000L
        assertEquals("3:23:27", Live.timerText(left, "hmmss"))
        assertEquals("203:27", Live.timerText(left, "mmss"))
        assertEquals("3h 23m", Live.timerText(left, "dhm"))
        assertEquals("2d 0h 5m", Live.timerText((2 * 86400 + 5 * 60) * 1000L, "dhm"))
        assertEquals("0:00:00", Live.timerText(-5000, "hmmss"))
        assertEquals("0m", Live.timerText(0, "dhm"))
    }
}
