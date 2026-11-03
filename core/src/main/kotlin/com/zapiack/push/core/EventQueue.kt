package com.zapiack.push.core

import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Events waiting to be sent, one file each. The app and its services both write here, and a file is only ever created whole and
 * removed whole, so neither can overwrite the other's events the way a shared list would.
 */
class EventQueue(private val directory: File) {
    private var sequence = 0

    init {
        directory.mkdirs()
    }

    @Synchronized
    fun add(event: SdkEvent, now: Instant = Instant.now()): Boolean {
        sequence += 1
        // The time first so files list in the order events happened, then a counter for events in the same millisecond.
        val name = "%015d-%06d-%s.json".format(now.toEpochMilli(), sequence, event.clientEventId)
        val temp = File(directory, "$name.tmp")
        return try {
            temp.writeText(event.toJson().toString())
            // A rename is whole or not at all: a reader never sees half an event.
            temp.renameTo(File(directory, name)).also { trim() }
        } catch (e: Exception) {
            temp.delete()
            false
        }
    }

    fun pending(): List<Pair<File, SdkEvent>> =
        (directory.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .sortedBy { it.name }
            .mapNotNull { file ->
                runCatching { file to SdkEvent.fromJson(JSONObject(file.readText())) }.getOrElse {
                    // A file nothing can read is never going to be sent.
                    file.delete()
                    null
                }
            }

    val count: Int get() = pending().size

    /** Sends what is waiting, a batch at a time. Stops at the first batch that has to be retried, leaving it in place. */
    fun flush(api: SdkApi, now: Instant = Instant.now()): Boolean {
        discardExpired(now)
        while (true) {
            val batch = pending().take(BATCH_SIZE)
            if (batch.isEmpty()) return true
            when (api.sendEvents(batch.map { it.second })) {
                SendOutcome.Retry -> return false
                // Sent, or refused for good: either way these events are finished with. Rejected ones are malformed, so dropped too.
                else -> batch.forEach { it.first.delete() }
            }
        }
    }

    private fun discardExpired(now: Instant) {
        for ((file, event) in pending()) if (Duration.between(event.at, now) > MAX_AGE) file.delete()
    }

    private fun trim() {
        val all = pending()
        if (all.size > MAX_EVENTS) all.take(all.size - MAX_EVENTS).forEach { it.first.delete() }
    }

    companion object {
        const val MAX_EVENTS = 500
        const val BATCH_SIZE = 100
        private val MAX_AGE: Duration = Duration.ofDays(6)
    }
}
