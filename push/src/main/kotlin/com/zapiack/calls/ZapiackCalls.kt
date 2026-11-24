package com.zapiack.calls

import android.content.Context
import android.content.Intent
import com.zapiack.realtime.TokenProvider
import com.zapiack.realtime.ZapiackRealtime
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.VideoTrack

/**
 * Voice and video calls on the phone's own WebRTC engine.
 *
 *     val call = ZapiackCalls.create(context) { myServer.callToken() }
 *     call.onTrack = { t -> (t.track.native as? VideoTrack)?.addSink(renderer) }
 *     call.join()
 *     call.enableMicrophone()
 *     call.enableCamera()
 *
 * Your server makes the room and a token for each person; the app never holds your API key. `renderer` is a
 * `SurfaceViewRenderer` that has been initialised with [eglBaseContext].
 */
object ZapiackCalls {
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var initialised = false

    /** The graphics context video views must be initialised with: `renderer.init(ZapiackCalls.eglBaseContext, null)`. */
    val eglBaseContext: EglBase.Context
        get() = checkNotNull(egl) { "Make a call first: ZapiackCalls.create(...)." }.eglBaseContext

    @Synchronized
    private fun engine(context: Context): Pair<PeerConnectionFactory, EglBase> {
        val e = egl ?: EglBase.create().also { egl = it }
        val f = factory ?: run {
            if (!initialised) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                initialised = true
            }
            PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
                .createPeerConnectionFactory()
                .also { factory = it }
        }
        return f to e
    }

    /**
     * A call. [tokens] asks your server for a call token (called on every connect, off the main thread, so an expired token is replaced).
     * [screenCapture] returns the result of the screen capture permission when the person has granted it, for `shareScreen`.
     */
    @JvmOverloads
    fun create(
        context: Context,
        url: String = ZapiackRealtime.DEFAULT_URL,
        forceTcp: Boolean = false,
        frontCamera: Boolean = true,
        screenCapture: (() -> Intent?)? = null,
        tokens: TokenProvider,
    ): ZapiackCall {
        val (f, e) = engine(context)
        return ZapiackCall(RealtimeSignal(ZapiackRealtime(tokens, url)), WebRtcLinkFactory(f), WebRtcCallMedia(context.applicationContext, f, e, screenCapture), forceTcp, frontCamera)
    }

    /**
     * Keeps the call going when the app leaves the screen (see [CallForegroundService]). Call it when the call is joined, from a screen that
     * is showing, and call [stopInCallService] when it ends. Returns false if Android could not find the service.
     */
    @JvmOverloads
    fun startInCallService(context: Context, title: String = "In a call", text: String? = null, smallIcon: Int = 0): Boolean {
        val intent = Intent(context, CallForegroundService::class.java)
            .putExtra(CallForegroundService.EXTRA_TITLE, title)
            .putExtra(CallForegroundService.EXTRA_TEXT, text)
            .putExtra(CallForegroundService.EXTRA_ICON, smallIcon)
        // False when Android cannot find the service: the app's manifest does not list it.
        return androidx.core.content.ContextCompat.startForegroundService(context, intent) != null
    }

    fun stopInCallService(context: Context) {
        context.startService(Intent(context, CallForegroundService::class.java).setAction(CallForegroundService.ACTION_STOP))
    }

    /** The engine's video track behind a received or sent track, to give to a `SurfaceViewRenderer` with `addSink`. */
    fun videoTrack(track: MediaTrack): VideoTrack? = track.native as? VideoTrack
}
