package com.zapiack.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps a call alive when the app leaves the screen. Android stops an app's microphone and camera shortly after it goes to the
 * background unless a foreground service of the right type is running, and shows the person a notification that a call is going on.
 *
 *     ZapiackCalls.startInCallService(context, title = "In a call", text = "with Ada", smallIcon = R.drawable.ic_call)
 *     ...
 *     ZapiackCalls.stopInCallService(context)
 *
 * Start it when the call is joined, from a screen that is showing (Android 12+ refuses to start one from the background), and stop it
 * when the call ends. The app needs the CAMERA and RECORD_AUDIO permissions granted first; this library's manifest adds the service and
 * the foreground service permissions.
 */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(intent?.getStringExtra(EXTRA_TITLE) ?: "In a call")
            .setContentText(intent?.getStringExtra(EXTRA_TEXT))
            .setSmallIcon(intent?.getIntExtra(EXTRA_ICON, 0)?.takeIf { it != 0 } ?: android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    companion object {
        internal const val CHANNEL = "zapiack_calls"
        internal const val NOTIFICATION_ID = 7417
        internal const val ACTION_STOP = "com.zapiack.calls.STOP"
        internal const val EXTRA_TITLE = "title"
        internal const val EXTRA_TEXT = "text"
        internal const val EXTRA_ICON = "icon"

        internal fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Calls", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while a call is going on" })
            }
        }
    }
}
