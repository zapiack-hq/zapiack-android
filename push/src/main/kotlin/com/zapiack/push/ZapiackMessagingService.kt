package com.zapiack.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.zapiack.push.core.Processing
import com.zapiack.push.core.PushBlock
import java.net.HttpURLConnection
import java.net.URL

/**
 * Receives pushes. Add it to your manifest through this library (it is merged in automatically), or, if your app already has
 * its own Firebase service, call [handle] from `onMessageReceived` and `ZapiackPush.onNewToken(context, token)` from `onNewToken`.
 */
open class ZapiackMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        ZapiackPush.attach(this)
    }

    override fun onNewToken(token: String) {
        ZapiackPush.onNewToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        handle(this, message)
    }

    companion object {
        private const val CHANNEL_ID = "zapiack_default"

        /** Returns true when the message was a Zapiack push and has been dealt with. */
        @JvmStatic
        fun handle(context: Context, message: RemoteMessage): Boolean {
            val block = PushBlock.fromData(message.data) ?: return false
            val client = ZapiackPush.clientFor(context) ?: return true
            client.received(block)

            // A push without buttons arrives as a notification message: the system shows it when the app is in the background, and it
            // reaches this method only when the app is in front. Showing it is then the app's choice, so it is shown here.
            val title = message.data["zapiack_title"] ?: message.notification?.title
            val body = message.data["zapiack_body"] ?: message.notification?.body
            if (title != null && ZapiackPush.hasPermission(context)) show(context, block, title, body)

            ZapiackPush.runInBackground { client.flush() }
            return true
        }

        private fun show(context: Context, block: PushBlock, title: String, body: String?) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Notifications", NotificationManager.IMPORTANCE_DEFAULT))
            }

            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(pending(context, launch, block, null, block.deliveryId.hashCode()))

            block.actions.take(3).forEachIndexed { i, action ->
                builder.addAction(0, action.title, pending(context, launch, block, action.id, block.deliveryId.hashCode() + i + 1))
            }

            val style = block.style
            if (style != null) {
                // A custom look: the thumbnail (or, failing that, the push's picture) sits beside the text.
                val thumbnail = (style.thumbnail ?: Processing.imageUrl(block))?.let { download(it) }?.let { StyledNotification.shrink(it) }
                // Not setLargeIcon: the system would draw the picture a second time, in the header, and squeeze the text.
                StyledNotification.apply(context, builder, style, title, body, thumbnail)
            } else {
                Processing.imageUrl(block)?.let { url ->
                    download(url)?.let { bitmap -> builder.setLargeIcon(bitmap).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap)) }
                }
            }

            runCatching { NotificationManagerCompat.from(context).notify(block.deliveryId.hashCode(), builder.build()) }
                .onFailure { android.util.Log.w("ZapiackPush", "Could not show the notification: ${it.message}", it) }
        }

        /** The intent carries what the tap needs, so it works whether or not the app was running. */
        private fun pending(context: Context, launch: Intent, block: PushBlock, actionId: String?, code: Int): PendingIntent {
            val intent = Intent(launch).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("zapiack_m", block.messageId)
                putExtra("zapiack_d", block.deliveryId)
                block.link?.let { putExtra("zapiack_l", it) }
                block.event?.let { putExtra("zapiack_e", it) }
                block.windowMinutes?.let { w -> putExtra("zapiack_t", """{"w":$w,"s":${block.screens ?: false}}""") }
                if (block.actions.isNotEmpty()) putExtra("zapiack_a", actionsJson(block))
                actionId?.let { putExtra(ZapiackPush.EXTRA_ACTION, it) }
            }
            return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        private fun actionsJson(block: PushBlock): String = org.json.JSONArray(block.actions.map {
            org.json.JSONObject().put("id", it.id).put("title", it.title).apply {
                it.event?.let { e -> put("event", e) }
                it.link?.let { l -> put("link", l) }
            }
        }).toString()

        private fun download(url: String): android.graphics.Bitmap? = runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            // Only a secure address, and only one answer: a redirect to somewhere else is not followed.
            connection.instanceFollowRedirects = false
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }
}
