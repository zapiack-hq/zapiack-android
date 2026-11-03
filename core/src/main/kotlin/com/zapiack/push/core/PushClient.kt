package com.zapiack.push.core

import org.json.JSONObject
import java.time.Instant

/** The state and rules behind the app-side SDK, without anything that needs a running app. */
class PushClient(
    val config: ZapiackConfig,
    private val storage: KeyValueStorage,
    val queue: EventQueue,
    transport: HttpTransport = UrlConnectionTransport(),
    private val now: () -> Instant = Instant::now,
) {
    private val api = SdkApi(config, transport)
    private var previousScreen: String? = null

    var deviceToken: String?
        get() = storage.get(SharedState.TOKEN)
        set(value) = storage.set(SharedState.TOKEN, value)

    // "set:<id>" or "clear": a change of user that has not reached the service yet.
    private var pendingUser: String?
        get() = storage.get(SharedState.USER)
        set(value) = storage.set(SharedState.USER, value)

    private var tags: Map<String, Any?>
        get() = storage.get(SharedState.TAGS)?.let { runCatching { JSONObject(it).toPlainMap() }.getOrNull() } ?: emptyMap()
        set(value) = storage.set(SharedState.TAGS, JSONObject(value).toString())

    fun login(userId: String) { pendingUser = "set:$userId" }
    fun logout() { pendingUser = "clear" }

    fun addTags(new: Map<String, Any?>) { tags = tags + new }
    fun removeTags(keys: Collection<String>) { tags = tags - keys.toSet() }

    /** Tells the service about this install. Sent on every start; a change of user goes once and is then forgotten. */
    fun register(permission: String?, appVersion: String?) {
        val token = deviceToken ?: return
        val user = pendingUser
        val link = when {
            user == null -> UserLink.Unchanged
            user == "clear" -> UserLink.Clear
            else -> UserLink.Set(user.removePrefix("set:"))
        }
        api.register(DeviceRegistration(config.installationId, token, link, tags.ifEmpty { null }, permission, appVersion = appVersion))
        // Only forget the change if it is still the one that was sent.
        if (pendingUser == user) pendingUser = null
    }

    var activeSession: ActiveSession?
        get() = storage.get(SharedState.SESSION)?.let(ActiveSession::fromJson)?.takeIf { it.isOpen(now()) }
        private set(value) = storage.set(SharedState.SESSION, value?.toJson())

    /** Reports a tap and opens its window. Returns what the app needs to route, or null when the push is not ours. */
    @Synchronized
    fun handleTap(block: PushBlock, actionId: String? = null): Tap? {
        val result = Processing.tap(block, actionId, now()) ?: return null
        // Last tap wins: a new tap replaces the window of the one before it.
        activeSession = result.session
        previousScreen = null
        result.events.forEach { queue.add(it, now()) }
        return result.tap
    }

    /** Records that a push arrived. */
    fun received(block: PushBlock) {
        queue.add(SdkEvent(EventType.Received, block.deliveryId, at = now()), now())
    }

    /** A screen the user reached. Only recorded while a tapped push's window is open and the push asked for screens. */
    @Synchronized
    fun screen(name: String) {
        val session = activeSession?.takeIf { it.screens } ?: return
        val from = previousScreen
        previousScreen = name
        queue.add(SdkEvent(EventType.Screen, session.deliveryId, sessionId = session.sessionId, name = name, previousScreen = from, at = now()), now())
    }

    /** A named thing the user did. Credited to the tapped push while its window is open; otherwise there is nothing to credit. */
    fun track(name: String, properties: Map<String, Any?>? = null): Boolean {
        val session = activeSession ?: return false
        return queue.add(SdkEvent(EventType.Custom, session.deliveryId, sessionId = session.sessionId, name = name, properties = properties, at = now()), now())
    }

    fun flush(): Boolean = queue.flush(api, now())
}
