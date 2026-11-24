package com.zapiack.push

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.zapiack.push.core.Live

/**
 * Draws a template push that has live parts. The countdown is the system's own Chronometer, counting down from the end time, so it
 * keeps ticking on the lock screen with no further push and no work from the app. When the timer ends, an alarm draws the
 * notification once more in its ended state: the expired text, no timer, or zeroes, as the template says.
 */
internal object LiveNotification {
    private val STEPS = intArrayOf(R.id.zapiack_live_step0, R.id.zapiack_live_step1, R.id.zapiack_live_step2, R.id.zapiack_live_step3, R.id.zapiack_live_step4, R.id.zapiack_live_step5)

    /** Fills in the builder. Returns when the timer ends (ms since 1970) if the notification should be drawn again then. */
    fun apply(
        context: Context,
        builder: NotificationCompat.Builder,
        live: List<Live>,
        title: String,
        body: String?,
        background: String?,
        thumbnail: Bitmap?,
        receivedAt: Long,
        ended: Boolean,
    ): Long? {
        val now = System.currentTimeMillis()
        val timer = live.filterIsInstance<Live.Timer>().firstOrNull()
        val endsAt = timer?.endsAt(receivedAt)
        val finished = ended || (endsAt != null && endsAt <= now)

        val small = build(context, live, title, body, background, thumbnail, now, endsAt, finished, compact = true)
        val big = build(context, live, title, body, background, thumbnail, now, endsAt, finished, compact = false)
        builder
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomHeadsUpContentView(small)
            .setCustomBigContentView(big)
            .setOnlyAlertOnce(true)

        // Past its end, the notification is dismissed an hour later: an offer that is over should not sit there all day.
        if (endsAt != null && Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter((endsAt - now).coerceAtLeast(0) + 3_600_000)
        return if (timer != null && endsAt != null && !finished) endsAt else null
    }

    private fun build(
        context: Context,
        live: List<Live>,
        title: String,
        body: String?,
        background: String?,
        thumbnail: Bitmap?,
        now: Long,
        endsAt: Long?,
        finished: Boolean,
        compact: Boolean,
    ): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.zapiack_live)

        // A template's own background: the tinted box, with text that reads on it. Without one the system's colours are used.
        val bg = background?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        val ink = bg?.let { if (StyledNotification.isLight(it)) Color.parseColor("#111827") else Color.WHITE }
        if (bg != null) {
            v.setViewVisibility(R.id.zapiack_live_bg, View.VISIBLE)
            v.setInt(R.id.zapiack_live_bg, "setColorFilter", bg)
        }

        v.setTextViewText(R.id.zapiack_live_title, title)
        ink?.let { v.setTextColor(R.id.zapiack_live_title, it) }
        if (!body.isNullOrBlank() && !compact) {
            v.setViewVisibility(R.id.zapiack_live_body, View.VISIBLE)
            v.setTextViewText(R.id.zapiack_live_body, body)
            ink?.let { v.setTextColor(R.id.zapiack_live_body, (it and 0x00FFFFFF) or (0xCC shl 24)) }
        }
        if (thumbnail != null) {
            v.setViewVisibility(R.id.zapiack_live_thumb, View.VISIBLE)
            v.setImageViewBitmap(R.id.zapiack_live_thumb, thumbnail)
        }

        for (el in live) when (el) {
            is Live.Timer -> timer(v, el, now, endsAt, finished, ink)
            is Live.Progress -> if (!compact) progress(v, el, ink)
            is Live.Counter -> {
                v.setViewVisibility(R.id.zapiack_live_counter, View.VISIBLE)
                v.setTextViewText(R.id.zapiack_live_counter, listOf(el.value, el.label).filter { it.isNotBlank() }.joinToString(" "))
                (el.color?.let { Color.parseColor(it) } ?: ink)?.let { v.setTextColor(R.id.zapiack_live_counter, it) }
            }
            is Live.Badge -> {
                v.setViewVisibility(R.id.zapiack_live_badge, View.VISIBLE)
                v.setTextViewText(R.id.zapiack_live_badge, el.text)
                el.color?.let { tint(v, R.id.zapiack_live_badge, Color.parseColor(it)) }
            }
            // Dates are already written into the title and body by the template, in the reader's words; nothing to draw apart.
            is Live.DateText -> Unit
        }
        return v
    }

    internal fun timer(v: RemoteViews, t: Live.Timer, now: Long, endsAt: Long?, finished: Boolean, ink: Int?) {
        if (endsAt == null) return
        val color = t.color?.let { Color.parseColor(it) } ?: ink
        val label = t.label.takeIf { it.isNotBlank() }

        if (finished) {
            when (t.onEnd) {
                "hide" -> return
                "expired" -> {
                    v.setViewVisibility(R.id.zapiack_live_timer_label, View.VISIBLE)
                    v.setTextViewText(R.id.zapiack_live_timer_label, t.expiredText.ifBlank { label ?: "" })
                    color?.let { v.setTextColor(R.id.zapiack_live_timer_label, it) }
                    return
                }
            }
        }

        v.setViewVisibility(R.id.zapiack_live_timer_row, View.VISIBLE)
        val remaining = if (finished) 0 else endsAt - now
        // The Chronometer counts down on its own from Android 7; it cannot show days, so that format is drawn as text.
        val ticks = t.tick && !finished && t.format != "dhm" && Build.VERSION.SDK_INT >= 24
        val id = if (ticks) R.id.zapiack_live_chrono else R.id.zapiack_live_timer_text
        v.setViewVisibility(id, View.VISIBLE)
        if (ticks) {
            v.setChronometer(id, SystemClock.elapsedRealtime() + remaining, null, true)
            v.setChronometerCountDown(id, true)
        } else {
            v.setTextViewText(id, Live.timerText(remaining, t.format))
        }

        if (t.look == "pills" && color != null) {
            tint(v, id, color)
            v.setTextColor(id, Color.WHITE)
            v.setViewPadding(id, 18, 4, 18, 4)
        } else {
            color?.let { v.setTextColor(id, it) }
        }

        if (label != null) {
            v.setViewVisibility(R.id.zapiack_live_timer_label, View.VISIBLE)
            v.setTextViewText(R.id.zapiack_live_timer_label, label)
            val labelColor = if (t.look == "pills") ink else color
            labelColor?.let { v.setTextColor(R.id.zapiack_live_timer_label, it) }
        }
    }

    internal fun progress(v: RemoteViews, p: Live.Progress, ink: Int?) {
        v.setViewVisibility(R.id.zapiack_live_progress, View.VISIBLE)
        v.setProgressBar(R.id.zapiack_live_progress, p.steps.size, p.current, false)
        if (p.color != null && Build.VERSION.SDK_INT >= 31) v.setColorStateList(R.id.zapiack_live_progress, "setProgressTintList", ColorStateList.valueOf(Color.parseColor(p.color)))
        v.setViewVisibility(R.id.zapiack_live_steps, View.VISIBLE)
        STEPS.forEachIndexed { i, id ->
            val step = p.steps.getOrNull(i)
            v.setViewVisibility(id, if (step == null) View.GONE else View.VISIBLE)
            if (step != null) {
                v.setTextViewText(id, step)
                ink?.let { v.setTextColor(id, (it and 0x00FFFFFF) or (0xAA shl 24)) }
            }
        }
    }

    /** A rounded fill in a colour. A tint keeps the rounded shape on Android 12 and later; before that a plain fill is used. */
    internal fun tint(v: RemoteViews, id: Int, color: Int) {
        if (Build.VERSION.SDK_INT >= 31) {
            v.setInt(id, "setBackgroundResource", R.drawable.zapiack_pill)
            v.setColorStateList(id, "setBackgroundTintList", ColorStateList.valueOf(color))
        } else {
            v.setInt(id, "setBackgroundColor", color)
        }
    }

    /**
     * Asks to draw the notification again when the timer ends. The alarm is inexact, so the system may run it a little late to save
     * battery; until then the countdown simply sits at zero. The push's data travels in the alarm, so nothing has to be stored.
     */
    fun scheduleEnd(context: Context, at: Long, notificationId: Int, data: Map<String, String>) {
        val intent = Intent(context, LiveTimerReceiver::class.java).apply {
            action = LiveTimerReceiver.ACTION
            putExtra(LiveTimerReceiver.EXTRA_ID, notificationId)
            putExtra(LiveTimerReceiver.EXTRA_DATA, HashMap(data))
        }
        val pending = PendingIntent.getBroadcast(context, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarms.set(AlarmManager.RTC, at, pending) }
    }
}
