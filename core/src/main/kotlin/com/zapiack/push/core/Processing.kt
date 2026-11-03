package com.zapiack.push.core

import java.net.URI
import java.time.Instant
import java.util.UUID

/** The decisions the app and its services share, kept apart from Android so they can be tested. */
object Processing {
    private val blockedSchemes = setOf("javascript", "data", "file", "vbscript")
    const val DEFAULT_WINDOW_MINUTES = 30

    /** A picture is only fetched from a secure address. */
    fun imageUrl(block: PushBlock): String? {
        val raw = block.image ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        return raw.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() }
    }

    /** Only links the app can route are handed over; a script address is never one. */
    fun link(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val scheme = runCatching { URI(raw).scheme }.getOrNull()?.lowercase() ?: return null
        return raw.takeIf { scheme !in blockedSchemes }
    }

    class TapResult(val tap: Tap, val session: ActiveSession, val events: List<SdkEvent>)

    /** Turns a tap into the events to report and the session it opens. `actionId` is null for a tap on the body. */
    fun tap(block: PushBlock, actionId: String?, at: Instant = Instant.now()): TapResult? {
        val action = actionId?.let { id -> block.actions.firstOrNull { it.id == id } }
        // A button this push did not declare is not reported as one.
        if (actionId != null && action == null) return null

        val sessionId = UUID.randomUUID().toString()
        val event = if (action != null) {
            SdkEvent(EventType.Action, block.deliveryId, sessionId = sessionId, actionId = action.id, name = action.event, at = at)
        } else {
            SdkEvent(EventType.Clicked, block.deliveryId, sessionId = sessionId, name = block.event, at = at)
        }
        val session = ActiveSession(sessionId, block.deliveryId, block.messageId, at, block.windowMinutes ?: DEFAULT_WINDOW_MINUTES, block.screens ?: false)
        val tap = Tap(block.messageId, block.deliveryId, sessionId, action?.id, action?.event ?: block.event, link(action?.link ?: block.link))
        return TapResult(tap, session, listOf(event))
    }
}
