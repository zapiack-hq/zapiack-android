package com.zapiack.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Wakes when a live notification's timer ends and draws it once more in its ended state. If the person already dismissed it, it
 * is left dismissed: a notification that is gone is not brought back.
 */
class LiveTimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        val data = (intent.getSerializableExtra(EXTRA_DATA) as? HashMap<String, String>) ?: return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val stillShown = android.os.Build.VERSION.SDK_INT < 23 || manager.activeNotifications.any { it.id == id }
        if (stillShown) ZapiackMessagingService.showFromData(context, data, ended = true)
    }

    companion object {
        const val ACTION = "com.zapiack.push.LIVE_TIMER_ENDED"
        const val EXTRA_ID = "zapiack_live_id"
        const val EXTRA_DATA = "zapiack_live_data"
    }
}
