package com.zapiack.push.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
class HttpResponse(val status: Int, val body: String)

/** Sends one request. Throws when the network fails; returns the answer, whatever its status, when it does not. */
fun interface HttpTransport {
    fun send(request: HttpRequest): HttpResponse
}

class UrlConnectionTransport(private val timeoutMs: Int = 20_000) : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            request.headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (request.body != null) {
                connection.doOutput = true
                connection.outputStream.use { it.write(request.body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return HttpResponse(status, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}

data class EventsResult(val accepted: Int, val ignored: Int, val rejected: List<Pair<Int, String>>)

sealed class SendOutcome {
    data class Sent(val result: EventsResult) : SendOutcome()

    /** The request did not get through, or the service was busy: keep the events and try again. */
    object Retry : SendOutcome()

    /** The request was refused for good: repeating it will be refused again. */
    data class Refused(val status: Int) : SendOutcome()
}

class RegistrationFailed(val status: Int) : Exception("The device could not be registered ($status)")

/** `externalUserId` has three states, and they mean different things to the service. */
sealed class UserLink {
    /** Leave the link as it is. */
    object Unchanged : UserLink()
    data class Set(val id: String) : UserLink()
    /** Sign out. */
    object Clear : UserLink()
}

class DeviceRegistration(
    val installationId: String,
    val token: String,
    val user: UserLink = UserLink.Unchanged,
    val tags: Map<String, Any?>? = null,
    val permission: String? = null,
    val environment: String? = null,
    val appVersion: String? = null,
    val platform: String = "android",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("installationId", installationId)
        put("platform", platform)
        put("token", token)
        when (user) {
            UserLink.Unchanged -> Unit
            UserLink.Clear -> put("externalUserId", JSONObject.NULL)
            is UserLink.Set -> put("externalUserId", user.id)
        }
        tags?.let { put("tags", JSONObject(it)) }
        permission?.let { put("permission", it) }
        environment?.let { put("environment", it) }
        appVersion?.let { put("appVersion", it) }
    }
}

class SdkApi(private val config: ZapiackConfig, private val transport: HttpTransport = UrlConnectionTransport()) {
    private fun request(path: String, method: String, body: String?) = HttpRequest(
        method,
        "${config.baseUrl.trimEnd('/')}/push/sdk/$path",
        mapOf("Content-Type" to "application/json", "X-Zapiack-App-Key" to config.appKey),
        body,
    )

    fun register(registration: DeviceRegistration) {
        val response = transport.send(request("devices", "PUT", registration.toJson().toString()))
        if (response.status !in 200..299) throw RegistrationFailed(response.status)
    }

    fun sendEvents(events: List<SdkEvent>): SendOutcome {
        val body = JSONObject().put("events", JSONArray(events.map { it.toJson() })).toString()
        val response = try {
            transport.send(request("events", "POST", body))
        } catch (e: Exception) {
            return SendOutcome.Retry
        }
        if (response.status in 200..299) {
            val parsed = runCatching { JSONObject(response.body) }.getOrNull()
            val rejected = parsed?.optJSONArray("rejected")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { r -> r.getInt("index") to r.getString("reason") } } } ?: emptyList()
            return SendOutcome.Sent(EventsResult(parsed?.optInt("accepted", events.size) ?: events.size, parsed?.optInt("ignored", 0) ?: 0, rejected))
        }
        // Busy or down: try again. Anything else (a wrong key, a bad request) will not improve by repeating it.
        return if (response.status == 429 || response.status == 408 || response.status >= 500) SendOutcome.Retry else SendOutcome.Refused(response.status)
    }
}
