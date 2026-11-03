package com.zapiack.push

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.zapiack.push.core.PushBlock

/**
 * Draws a push that asked for a look: a coloured box, a thumbnail beside the text, and a shine that sweeps across.
 *
 * A notification can only animate with the few view types the system allows in one, so the shine is a ViewFlipper that alternates
 * a translucent band with an empty view; each time the band appears it travels across the box.
 */
internal object StyledNotification {
    private const val DEFAULT_COLOR = "#1F2937"

    fun apply(context: Context, builder: NotificationCompat.Builder, style: PushBlock.Style, title: String, body: String?, thumbnail: Bitmap?) {
        val background = parse(style.color)
        val views = build(context, style, title, body, thumbnail, background)
        builder
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(views)
            .setCustomHeadsUpContentView(views)
            .setCustomBigContentView(views)
    }

    private fun build(context: Context, style: PushBlock.Style, title: String, body: String?, thumbnail: Bitmap?, background: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.zapiack_notification)
        views.setInt(R.id.zapiack_bg, "setColorFilter", background)

        val text = if (isLight(background)) Color.parseColor("#111827") else Color.WHITE
        views.setTextViewText(R.id.zapiack_title, title)
        views.setTextColor(R.id.zapiack_title, text)
        views.setTextViewText(R.id.zapiack_body, body ?: "")
        views.setTextColor(R.id.zapiack_body, (text and 0x00FFFFFF) or (0xCC shl 24))
        views.setViewVisibility(R.id.zapiack_body, if (body.isNullOrBlank()) View.GONE else View.VISIBLE)

        if (thumbnail != null) views.setImageViewBitmap(R.id.zapiack_thumb, thumbnail) else views.setViewVisibility(R.id.zapiack_thumb, View.GONE)
        views.setViewVisibility(R.id.zapiack_shine, if (style.shine) View.VISIBLE else View.GONE)
        return views
    }

    /** The colour was checked as #RRGGBB when the push was read; anything else gets a calm dark default rather than a crash. */
    internal fun parse(color: String?): Int = runCatching { Color.parseColor(color ?: DEFAULT_COLOR) }.getOrElse { Color.parseColor(DEFAULT_COLOR) }

    /** Whether dark text reads better on this colour (perceived brightness, not just the average of the channels). */
    internal fun isLight(color: Int): Boolean = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255 > 0.6

    /** A thumbnail is small in a notification; a large one would only waste the system's limit on what a notification may carry. */
    fun shrink(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= 192) return bitmap
        val scale = 192f / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }
}
