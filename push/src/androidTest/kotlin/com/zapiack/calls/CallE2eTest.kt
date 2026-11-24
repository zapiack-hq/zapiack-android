package com.zapiack.calls

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real phone engine talking to the real servers: this device's libwebrtc, our gateway and our mediasoup media server, with a Chrome on
 * the other side. It receives Chrome's microphone and camera, sends its own, mutes, and leaves. Run from `media.native.spec.ts` in the
 * backend, which starts the servers and Chrome and passes WS_URL and TOKEN as instrumentation arguments.
 */
@RunWith(AndroidJUnit4::class)
class CallE2eTest {
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    private suspend fun until(what: String, seconds: Long = 40, ok: () -> Boolean) {
        withTimeout(seconds * 1000) { while (!ok()) delay(100) }
    }

    @Test fun joinsReceivesSendsMutesAndLeavesAgainstTheRealMediaServer() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val ws = args.getString("WS_URL")
        val token = args.getString("TOKEN")
        assumeTrue("WS_URL and TOKEN are passed by the backend's media.native.spec.ts", ws != null && token != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val call = ZapiackCalls.create(context, url = ws!!) { token!! }
        val received = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        call.onTrack = { received += "${it.identity}:${it.source.wire}" }
        call.onError = { errors += it.message ?: "error" }

        val (role, _, members) = call.join()
        until("ada's microphone and camera") { received.size >= 2 }

        // Media can take a few seconds to start: wait for it. An emulator's microphone is silent and the client sends Opus with DTX, which sends
        // almost nothing in silence, so audio only has to have arrived.
        var stats = call.stats()
        var i = 0
        while (i++ < 15 && !stats.all { it.bytesReceived > (if (it.source == TrackSource.AUDIO) 0 else 2000) }) {
            delay(2000)
            stats = call.stats()
        }
        val flowing = stats.map { "${it.source.wire}=${it.bytesReceived}" }

        call.enableMicrophone()
        call.enableCamera()
        val sending = call.sending.map { it.wire }.sorted()
        // Chrome measures what arrives from here while this holds.
        delay(12_000)
        call.setMuted(TrackSource.AUDIO, true)
        delay(2000)
        val mutedWhileMuted = call.isMuted(TrackSource.AUDIO)
        call.setMuted(TrackSource.AUDIO, false)
        delay(2000)
        call.stop(TrackSource.VIDEO)
        delay(2000)
        call.leave()

        println("E2E RESULT role=$role members=${members.map { it.identity }} received=${received.sorted()} flowing=$flowing sending=$sending errors=$errors")
        assertTrue(errors.isEmpty(), "errors: $errors")
        assertEquals(CallRole.HOST, role)
        assertEquals(listOf("ada:audio", "ada:video"), received.sorted())
        assertTrue(stats.all { it.bytesReceived > (if (it.source == TrackSource.AUDIO) 0 else 2000) }, "media is really arriving: $flowing")
        assertEquals(listOf("audio", "video"), sending)
        assertTrue(mutedWhileMuted)
        assertEquals(CallState.CLOSED, call.state)
    }
}
