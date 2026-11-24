package com.zapiack.calls

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import java.util.UUID

/** Camera, microphone, screen and speaker through the Android WebRTC library. */
internal class WebRtcCallMedia(
    private val context: Context,
    private val factory: PeerConnectionFactory,
    private val egl: EglBase,
    /** The permission result for screen capture (from `MediaProjectionManager.createScreenCaptureIntent()`), if the app has one. */
    private val screenCapture: (() -> Intent?)?,
) : CallMedia {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val capturers = HashMap<String, VideoCapturer>()

    override suspend fun microphone(): MediaTrack {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val source = factory.createAudioSource(constraints)
        val track = factory.createAudioTrack(UUID.randomUUID().toString(), source)
        return WebRtcTrack(track) { source.dispose() }
    }

    override suspend fun camera(front: Boolean): MediaTrack {
        val enumerator = Camera2Enumerator(context)
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) == front }
            ?: enumerator.deviceNames.firstOrNull()
            ?: throw IllegalStateException("No camera was found.")
        val capturer = enumerator.createCapturer(name, null) ?: throw IllegalStateException("The camera could not be opened.")
        return video(capturer, 1280, 720, 30, screen = false)
    }

    override suspend fun screen(): MediaTrack {
        val intent = screenCapture?.invoke() ?: throw IllegalStateException("Screen sharing needs the result of the screen capture permission: pass `screenCapture` when creating the call.")
        val capturer = ScreenCapturerAndroid(intent, object : android.media.projection.MediaProjection.Callback() {})
        return video(capturer, 1280, 720, 15, screen = true)
    }

    private fun video(capturer: VideoCapturer, width: Int, height: Int, fps: Int, screen: Boolean): MediaTrack {
        val helper = SurfaceTextureHelper.create("zapiack-capture", egl.eglBaseContext)
        val source = factory.createVideoSource(screen)
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(width, height, fps)
        val track = factory.createVideoTrack(UUID.randomUUID().toString(), source)
        capturers[track.id()] = capturer
        return WebRtcTrack(track) {
            capturers.remove(track.id())
            runCatching { capturer.stopCapture() }
            capturer.dispose()
            source.dispose()
            helper.dispose()
        }
    }

    override fun setSpeaker(on: Boolean) {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audio.isSpeakerphoneOn = on
    }

    override fun switchCamera(track: MediaTrack) {
        (capturers[track.id] as? CameraVideoCapturer)?.switchCamera(null) ?: throw IllegalStateException("That track is not a camera.")
    }
}
