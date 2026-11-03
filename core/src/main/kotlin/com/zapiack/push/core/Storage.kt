package com.zapiack.push.core

import java.util.UUID

/** Where the SDK keeps what has to survive the app being closed. */
interface KeyValueStorage {
    fun get(key: String): String?
    fun set(key: String, value: String?)
}

class MemoryStorage : KeyValueStorage {
    private val values = HashMap<String, String>()

    @Synchronized override fun get(key: String): String? = values[key]

    @Synchronized override fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

data class ZapiackConfig(val appKey: String, val baseUrl: String, val installationId: String)

object SharedState {
    const val DEFAULT_BASE_URL = "https://api.zapiack.com/v1"

    internal const val TOKEN = "zapiack.token"
    internal const val SESSION = "zapiack.session"
    internal const val USER = "zapiack.user"
    internal const val TAGS = "zapiack.tags"
    private const val APP_KEY = "zapiack.appKey"
    private const val BASE_URL = "zapiack.baseUrl"
    private const val INSTALLATION = "zapiack.installationId"

    /** A UUID made on first use and kept: it identifies this install even when its push address changes. */
    fun installationId(storage: KeyValueStorage): String =
        storage.get(INSTALLATION)?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString().also { storage.set(INSTALLATION, it) }

    fun save(storage: KeyValueStorage, appKey: String, baseUrl: String) {
        storage.set(APP_KEY, appKey)
        storage.set(BASE_URL, baseUrl)
    }

    /** What a background service reads when a push arrives and the app is not running. */
    fun load(storage: KeyValueStorage): ZapiackConfig? {
        val key = storage.get(APP_KEY)?.takeIf { it.startsWith("pk_") } ?: return null
        val base = storage.get(BASE_URL) ?: return null
        return ZapiackConfig(key, base, installationId(storage))
    }
}
