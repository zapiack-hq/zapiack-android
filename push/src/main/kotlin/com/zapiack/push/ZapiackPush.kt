package com.zapiack.push

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.zapiack.push.core.EventQueue
import com.zapiack.push.core.PushBlock
import com.zapiack.push.core.PushClient
import com.zapiack.push.core.SharedState
import com.zapiack.push.core.Tap
import java.io.File
import java.util.concurrent.Executors

/** Push notifications for an app: ask permission, register this install, and follow what happens after a tap. */
object ZapiackPush {
    const val DEFAULT_BASE_URL = SharedState.DEFAULT_BASE_URL
    internal const val EXTRA_ACTION = "zapiack_action"
    private const val REQUEST_CODE = 7391

    private val background = Executors.newSingleThreadExecutor()
    @Volatile private var client: PushClient? = null
    @Volatile private var appVersion: String? = null

    /** Call once in `Application.onCreate`, before anything else. */
    @JvmStatic
    @JvmOverloads
    fun configure(context: Context, appKey: String, baseUrl: String = DEFAULT_BASE_URL) {
        require(appKey.startsWith("pk_")) { "ZapiackPush needs the public app key, which starts with pk_" }
        val app = context.applicationContext
        appContext = app
        val storage = SharedPrefsStorage(app)
        SharedState.save(storage, appKey, baseUrl)
        appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        client = buildClient(app)
        refreshToken()
        background.execute { client?.flush() }
    }

    /** Builds the client from what `configure` saved, so a service started by the system in a fresh process can use it. */
    internal fun clientFor(context: Context): PushClient? {
        client?.let { return it }
        return synchronized(this) { client ?: buildClient(context.applicationContext).also { client = it } }
    }

    private fun buildClient(app: Context): PushClient? {
        val storage = SharedPrefsStorage(app)
        val config = SharedState.load(storage) ?: return null
        return PushClient(config, storage, EventQueue(File(app.filesDir, "zapiack-events")))
    }

    internal fun runInBackground(block: () -> Unit) = background.execute { runCatching(block) }

    /** The identifier that ties this install to the service. */
    val installationId: String? get() = client?.config?.installationId

    /** Whether the app may show notifications right now. */
    @JvmStatic
    fun hasPermission(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Asks for permission on Android 13 and later; on earlier versions notifications are allowed already. Call
     * [onPermissionResult] from your activity's `onRequestPermissionsResult` to register once it is answered.
     */
    @JvmStatic
    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(activity)) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        } else {
            refreshToken()
        }
    }

    @JvmStatic
    fun onPermissionResult(requestCode: Int) {
        if (requestCode == REQUEST_CODE) refreshToken()
    }

    private fun refreshToken() {
        // An app that has not set Firebase up yet must still start; the token simply is not fetched.
        runCatching { FirebaseMessaging.getInstance().token.addOnSuccessListener { onNewToken(it) } }
            .onFailure { android.util.Log.w("Zapiack", "Firebase is not set up, so no push address was fetched: ${it.message}") }
    }

    /** For an app that has its own Firebase service: call this from its `onNewToken`. */
    @JvmStatic
    fun onNewToken(context: Context, token: String) {
        attach(context)
        clientFor(context)
        onNewToken(token)
    }

    internal fun onNewToken(token: String) {
        val c = client ?: return
        c.deviceToken = token
        sync()
    }

    private fun sync() {
        val c = client ?: return
        background.execute {
            runCatching { c.register(permission = if (c.deviceToken != null) permissionLabel() else null, appVersion = appVersion) }
        }
    }

    private var appContext: Context? = null
    private fun permissionLabel(): String? = appContext?.let { if (hasPermission(it)) "granted" else "denied" }

    @JvmStatic fun login(userId: String) { client?.login(userId); sync() }
    @JvmStatic fun logout() { client?.logout(); sync() }
    @JvmStatic fun addTags(tags: Map<String, Any?>) { client?.addTags(tags); sync() }
    @JvmStatic fun removeTags(keys: List<String>) { client?.removeTags(keys); sync() }

    /**
     * Call with the intent that opened an activity, in `onCreate` and `onNewIntent`. Returns what was tapped so the app can route,
     * or null when the intent did not come from a Zapiack notification.
     */
    @JvmStatic
    fun handleIntent(intent: Intent?): Tap? {
        val extras = intent?.extras ?: return null
        @Suppress("DEPRECATION")
        val data = extras.keySet().filter { it.startsWith("zapiack_") }.associateWith { extras.get(it)?.toString() }
        val block = PushBlock.fromData(data) ?: return null
        val tap = client?.handleTap(block, data[EXTRA_ACTION]) ?: return null
        // A tap is handled once: a rotation or a recreate must not report it again.
        data.keys.forEach { intent.removeExtra(it) }
        background.execute { client?.flush() }
        return tap
    }

    /** A screen the user reached, credited to the push they tapped while its window is open. */
    @JvmStatic fun screen(name: String) { client?.screen(name) }

    /** A named goal or event, credited to the push they tapped while its window is open. Returns false when there is nothing to credit. */
    @JvmStatic
    @JvmOverloads
    fun track(name: String, properties: Map<String, Any?>? = null): Boolean {
        val credited = client?.track(name, properties) ?: false
        if (credited) background.execute { client?.flush() }
        return credited
    }

    /** Sends anything waiting. Call when the app comes to the foreground. */
    @JvmStatic fun flush() { background.execute { client?.flush() } }

    internal fun attach(context: Context) { appContext = context.applicationContext }
}
