package com.zapiack.push

import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.zapiack.push.core.LayoutTree
import com.zapiack.push.core.Live
import com.zapiack.push.core.PushBlock

/**
 * Draws a push template laid out on the grid. The server cuts the design into rows and stacks; each row shares its width by the
 * columns its parts take (one prebuilt cell per weight, since a notification cannot set a weight in code), and each element gets
 * the height it was given on Android 12 and later. Buttons are drawn where they were placed and work when tapped.
 */
internal object LayoutNotification {
    private val CELLS = intArrayOf(
        R.layout.zapiack_cell_w1, R.layout.zapiack_cell_w2, R.layout.zapiack_cell_w3, R.layout.zapiack_cell_w4, R.layout.zapiack_cell_w5, R.layout.zapiack_cell_w6,
        R.layout.zapiack_cell_w7, R.layout.zapiack_cell_w8, R.layout.zapiack_cell_w9, R.layout.zapiack_cell_w10, R.layout.zapiack_cell_w11, R.layout.zapiack_cell_w12,
        R.layout.zapiack_cell_w13, R.layout.zapiack_cell_w14, R.layout.zapiack_cell_w15, R.layout.zapiack_cell_w16, R.layout.zapiack_cell_w17, R.layout.zapiack_cell_w18,
        R.layout.zapiack_cell_w19, R.layout.zapiack_cell_w20, R.layout.zapiack_cell_w21, R.layout.zapiack_cell_w22, R.layout.zapiack_cell_w23, R.layout.zapiack_cell_w24,
    )
    /** A collapsed notification has room for about this many grid rows; what does not fit whole waits for the expanded view. */
    private const val COLLAPSED_ROWS = 6

    class Content(
        val title: String,
        val body: String?,
        val live: List<Live>,
        val actions: List<PushBlock.Action>,
        val thumbnail: Bitmap?,
        val picture: Bitmap?,
        val background: String?,
        val receivedAt: Long,
        val ended: Boolean,
        /** The tap for the n-th button. */
        val actionIntent: (Int) -> PendingIntent,
    )

    /** Fills in the builder. Returns when a timer ends (ms since 1970) if the notification should be drawn again then. */
    fun apply(context: Context, builder: NotificationCompat.Builder, tree: LayoutTree.Node, content: Content): Long? {
        val now = System.currentTimeMillis()
        val timer = content.live.filterIsInstance<Live.Timer>().firstOrNull()
        val endsAt = timer?.endsAt(content.receivedAt)
        val finished = content.ended || (endsAt != null && endsAt <= now)

        val big = root(context, tree, content, now, endsAt, finished)
        val small = LayoutTree.top(tree, COLLAPSED_ROWS)?.let { root(context, it, content, now, endsAt, finished) } ?: big
        builder
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomHeadsUpContentView(small)
            .setCustomBigContentView(big)
            .setOnlyAlertOnce(true)
        if (endsAt != null && Build.VERSION.SDK_INT >= 26) builder.setTimeoutAfter((endsAt - now).coerceAtLeast(0) + 3_600_000)
        return if (timer != null && endsAt != null && !finished) endsAt else null
    }

    private fun root(context: Context, tree: LayoutTree.Node, c: Content, now: Long, endsAt: Long?, finished: Boolean): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.zapiack_layout_root)
        val bg = c.background?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        if (bg != null) {
            v.setViewVisibility(R.id.zapiack_live_bg, View.VISIBLE)
            v.setInt(R.id.zapiack_live_bg, "setColorFilter", bg)
        }
        val ink = bg?.let { if (StyledNotification.isLight(it)) Color.parseColor("#111827") else Color.WHITE }
        v.addView(R.id.zapiack_tree, node(context, tree, c, ink, now, endsAt, finished))
        return v
    }

    private fun node(context: Context, n: LayoutTree.Node, c: Content, ink: Int?, now: Long, endsAt: Long?, finished: Boolean): RemoteViews {
        val v = RemoteViews(context.packageName, if (n.row) R.layout.zapiack_tree_row else R.layout.zapiack_tree_col)
        for (child in n.children) {
            val inner: RemoteViews? = when (child) {
                is LayoutTree.Child.Sub -> node(context, child.node, c, ink, now, endsAt, finished)
                is LayoutTree.Child.Element -> element(context, child, c, ink, now, endsAt, finished)
                is LayoutTree.Child.Space -> if (n.row) null else space(context, child.weight)
            }
            if (n.row) {
                val cell = RemoteViews(context.packageName, CELLS[child.weight.coerceIn(1, CELLS.size) - 1])
                inner?.let { cell.addView(R.id.zapiack_cell, it) }
                v.addView(R.id.zapiack_container, cell)
            } else if (inner != null) {
                v.addView(R.id.zapiack_container, inner)
            }
        }
        return v
    }

    /** Empty rows in a stack: set exactly on Android 12 and later, built from fixed steps before that. */
    private fun space(context: Context, rows: Int): RemoteViews {
        val dp = rows * LayoutTree.ROW_DP
        if (Build.VERSION.SDK_INT >= 31) {
            val s = RemoteViews(context.packageName, R.layout.zapiack_space_8)
            s.setViewLayoutHeight(R.id.zapiack_space, dp.toFloat(), TypedValue.COMPLEX_UNIT_DIP)
            return s
        }
        val holder = RemoteViews(context.packageName, R.layout.zapiack_tree_col)
        var left = dp
        for ((size, layout) in listOf(64 to R.layout.zapiack_space_64, 32 to R.layout.zapiack_space_32, 16 to R.layout.zapiack_space_16, 8 to R.layout.zapiack_space_8)) {
            while (left >= size) {
                holder.addView(R.id.zapiack_container, RemoteViews(context.packageName, layout))
                left -= size
            }
        }
        return holder
    }

    private fun element(context: Context, e: LayoutTree.Child.Element, c: Content, ink: Int?, now: Long, endsAt: Long?, finished: Boolean): RemoteViews? {
        val pkg = context.packageName
        val inner: RemoteViews = when {
            e.ref == "t" -> RemoteViews(pkg, R.layout.zapiack_el_title).apply {
                setTextViewText(R.id.zapiack_text, c.title)
                ink?.let { setTextColor(R.id.zapiack_text, it) }
            }
            e.ref == "b" -> RemoteViews(pkg, R.layout.zapiack_el_text).apply {
                setTextViewText(R.id.zapiack_text, c.body ?: "")
                ink?.let { setTextColor(R.id.zapiack_text, (it and 0x00FFFFFF) or (0xCC shl 24)) }
            }
            e.ref == "i" || e.ref == "p" -> {
                val bitmap = (if (e.ref == "i") c.thumbnail else c.picture) ?: return null
                RemoteViews(pkg, R.layout.zapiack_el_image).apply { setImageViewBitmap(R.id.zapiack_image, bitmap) }
            }
            e.ref.startsWith("a") -> {
                val index = e.ref.drop(1).toIntOrNull() ?: return null
                val action = c.actions.getOrNull(index) ?: return null
                RemoteViews(pkg, R.layout.zapiack_el_button).apply {
                    setTextViewText(R.id.zapiack_button, action.title)
                    val primary = index == 0
                    val fill = if (primary) (ink ?: Color.parseColor("#111113")) else Color.argb(36, 127, 127, 127)
                    LiveNotification.tint(this, R.id.zapiack_button, fill)
                    setTextColor(R.id.zapiack_button, if (primary) (if (ink == Color.WHITE) Color.parseColor("#111113") else Color.WHITE) else (ink ?: Color.parseColor("#111113")))
                    setOnClickPendingIntent(R.id.zapiack_button, c.actionIntent(index))
                }
            }
            e.ref.startsWith("l") -> {
                val live = c.live.getOrNull(e.ref.drop(1).toIntOrNull() ?: return null) ?: return null
                when (live) {
                    is Live.Timer -> RemoteViews(pkg, R.layout.zapiack_el_timer).also { LiveNotification.timer(it, live, now, endsAt, finished, ink) }
                    is Live.Progress -> RemoteViews(pkg, R.layout.zapiack_el_progress).also { LiveNotification.progress(it, live, ink) }
                    is Live.Counter -> RemoteViews(pkg, R.layout.zapiack_el_text).apply {
                        setTextViewText(R.id.zapiack_text, listOf(live.value, live.label).filter { it.isNotBlank() }.joinToString(" "))
                        (live.color?.let { Color.parseColor(it) } ?: ink)?.let { setTextColor(R.id.zapiack_text, it) }
                    }
                    is Live.Badge -> RemoteViews(pkg, R.layout.zapiack_el_badge).apply {
                        setTextViewText(R.id.zapiack_live_badge, live.text)
                        live.color?.let { LiveNotification.tint(this, R.id.zapiack_live_badge, Color.parseColor(it)) }
                    }
                    // Dates arrive written out by the server, in the reader's words.
                    is Live.DateText -> RemoteViews(pkg, R.layout.zapiack_el_text).apply {
                        setTextViewText(R.id.zapiack_text, live.value)
                        ink?.let { setTextColor(R.id.zapiack_text, it) }
                    }
                }
            }
            else -> return null
        }
        val box = RemoteViews(pkg, R.layout.zapiack_el_box)
        box.addView(R.id.zapiack_box, inner)
        if (Build.VERSION.SDK_INT >= 31) box.setViewLayoutHeight(R.id.zapiack_box, (e.rows * LayoutTree.ROW_DP).toFloat(), TypedValue.COMPLEX_UNIT_DIP)
        return box
    }
}
