# Zapiack for Android

The one Zapiack library for Android apps. Push notifications, live messaging and voice and video calls are in it; new Zapiack products arrive in the same library.

Kotlin library. Android 5.0 (API 21) and later. Works from Kotlin and Java.

## Install

```kotlin
dependencies {
    implementation("com.zapiack:zapiack-android:0.2.0")
}
```

The app needs Firebase Cloud Messaging set up as usual (the `google-services.json` file and the Google services plugin).

## Set up

In your `Application`:

```kotlin
class ShopApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ZapiackPush.configure(this, appKey = "pk_…")
    }
}
```

The library registers its own Firebase service in your manifest. If your app already has one, remove ours and call into the library
from yours instead:

```kotlin
class MyService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = ZapiackPush.onNewToken(this, token)
    override fun onMessageReceived(message: RemoteMessage) {
        if (!ZapiackMessagingService.handle(this, message)) { /* your own pushes */ }
    }
}
```

Ask for permission when it makes sense for your users (Android 13 and later show a prompt; earlier versions need nothing):

```kotlin
ZapiackPush.requestPermission(this)            // in an Activity

override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    ZapiackPush.onPermissionResult(requestCode)
}
```

## Taps

Pass the intent that opened your activity, in `onCreate` and `onNewIntent`:

```kotlin
ZapiackPush.handleIntent(intent)?.let { tap ->
    tap.link?.let { /* route to it */ }
    // tap.actionId is the button that was tapped, tap.event the event name
}
```

## Users, tags and goals

```kotlin
ZapiackPush.login("user_123")
ZapiackPush.logout()
ZapiackPush.addTags(mapOf("plan" to "pro"))

ZapiackPush.screen("OrderDetails")                       // credited to the push the user tapped
ZapiackPush.track("purchase", mapOf("total" to 49))
```

Screens and goals are credited to a push only for a short window after it is tapped.

A push without buttons is shown by the system while your app is in the background, so the library can report its arrival only when
your app is in front. Pushes with buttons are always handled by the library.

## Custom looks

A push can ask for a colour, a thumbnail beside the text, and a shine that sweeps across it. The library draws these itself, so there is nothing to set up. If your app has no launcher icon it uses a system icon for the notification header, and any failure to show a notification is logged under the tag `ZapiackPush`.

## Realtime

Live messages over one WebSocket: chat, live updates, presence, and signalling for calls. `zapiack-android` brings it in with push; or depend on `com.zapiack:zapiack-realtime` alone (it uses OkHttp).

Your app never holds an API key. Your **server** asks Zapiack for a short-lived token for the signed-in user (the key needs the **Realtime** scope, or no scope limits), and hands it to the app:

```bash
curl -X POST https://api.zapiack.com/v1/realtime/tokens \
  -H "Authorization: Bearer $ZAPIACK_API_KEY" -H "Content-Type: application/json" \
  -d '{"userId":"user_42","capabilities":{"chat:*":["subscribe","publish","presence"]}}'
# → { "token": "eyJ…", "expiresAt": "…", "url": "wss://rt.zapiack.com/?token=eyJ…" }
```

`capabilities` says what that user may do on which channels (`*` matches anything): `subscribe`, `publish`, `presence`, `history`, `signal`. Tokens last an hour unless you ask for less (`ttlSeconds`, at most a day).

In the app:

```kotlin
import com.zapiack.realtime.ZapiackRealtime

val realtime = ZapiackRealtime(tokens = {
    // Ask YOUR server for a token. Called off the main thread on every connect, so an expired token is replaced.
    URL("https://your.server/realtime-token").readText()
})
realtime.connect { result -> result.onFailure { /* the project refuses this client */ } }

val sub = realtime.subscribe("chat:room-1") { m -> println("${m.from}: ${m.data}") }
realtime.publish("chat:room-1", mapOf("text" to "hi"), persist = true) { result -> /* confirmed, or why not */ }

val here = realtime.presence("chat:room-1")
here.enter(mapOf("name" to "Ann"))
realtime.signal("call-7", otherConnectionId, "offer", mapOf("sdp" to sdp))
realtime.onError = { e -> println("${e.code} ${e.message}") }
sub.cancel()
```

Everything runs on one background thread, so messages go out in order. Callbacks arrive on it too: post to the main thread before touching views. Java works the same way (`new ZapiackRealtime(() -> token)`).

- **Reconnects by itself** with a growing, jittered pause, fetches a fresh token each time, re-joins every channel and replays what it missed since the last message it saw (publish with `persist` to make a message replayable). Messages already seen are not repeated.
- **Sends made while offline** wait (up to 100) and go out in order once the line is back.
- A message that cannot be replayed because the client was away longer than the project's retention is reported as an error with code 410: reload that channel's history over REST.
- The project can refuse a client for good (Realtime switched off, or the connection was removed in the dashboard). Then `connect` fails with code 403 and it does not retry.

## Voice and video calls

One-to-one and group calls: microphone, camera (front and back), screen sharing, mute, speaker, and recovery when the phone changes network. The call protocol client is written for this SDK, so there is no mediasoup library behind it; the engine is Google's libwebrtc (`io.github.webrtc-sdk:android`), which comes with this library.

Add the permissions to your manifest and ask for camera and microphone access the way you do for any app:

```xml
<uses-permission android:name="android.permission.CAMERA"/>
<uses-permission android:name="android.permission.RECORD_AUDIO"/>
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/>
```

Your server makes the room and a token for each person (`POST /v1/calls/rooms` and `POST /v1/calls/tokens`, or `POST /v1/calls` to ring someone) and gives the token to the app. The app never holds your API key.

```kotlin
val call = ZapiackCalls.create(context) { myServer.callToken() }   // called on every connect, off the main thread

val renderer = findViewById<SurfaceViewRenderer>(R.id.remote).apply { init(ZapiackCalls.eglBaseContext, null) }
call.onTrack = { t -> ZapiackCalls.videoTrack(t.track)?.addSink(renderer) }   // audio plays by itself
call.onTrackEnded = { remove(it.identity, it.source) }
call.onEnded = { reason -> finish() }

lifecycleScope.launch {
    call.join()
    call.enableMicrophone()
    call.enableCamera()          // the front camera
    call.setSpeaker(true)
    call.switchCamera()          // front <-> back, without interrupting the video
    call.setMuted(TrackSource.AUDIO, true)
    call.leave()
}
```

The functions that talk to the server are `suspend`: call them from a coroutine. Callbacks arrive on one background thread, so hand work to your main thread before touching views. What a person may send comes from their token: a `listener` can receive but never send.

| | |
|---|---|
| `join()` | Connects and joins the room the token is for. |
| `enableMicrophone()`, `enableCamera()`, `shareScreen()` | Start sending. Each can be given your own track. |
| `setMuted(source, muted)`, `isMuted(source)` | Nothing is sent while muted, and the others are told. |
| `switchCamera()`, `setSpeaker(on)` | Front or back camera; loudspeaker or earpiece. |
| `stop(source)`, `leave()` | Stop one track (the camera light goes off) or leave. |
| `onTrack`, `onTrackEnded`, `onPaused`, `onPeerJoined`, `onPeerLeft`, `onEnded`, `onError` | What happens in the room. |
| `stats()` | Bytes, packets, losses and jitter per received track. |

On a phone the network changes under a call. The client gives a path that goes `disconnected` a few seconds to recover, then restarts it with fresh parameters from the server. If the connection to the server itself dropped, it rejoins the room and picks up who is there now.

**Keeping a call alive in the background.** Android stops the microphone and camera of an app that leaves the screen. Start the in-call service when the call is joined, from a screen that is showing, and stop it when the call ends. It shows the ongoing-call notification Android requires:

```kotlin
ZapiackCalls.startInCallService(context, title = "In a call", text = "with Ada", smallIcon = R.drawable.ic_call)   // false if the manifest does not list it
ZapiackCalls.stopInCallService(context)
```

The library's manifest adds the service and the `FOREGROUND_SERVICE_MICROPHONE` and `FOREGROUND_SERVICE_CAMERA` permissions; the person must still have granted camera and microphone access. **This service compiles and the system accepts the start, but it has not been run on a device**: an instrumentation test could not start it (the test process never ran the service), so try it in your app before relying on it. A call does not need to be restarted if the network changes or an event is missed: the client re-asks the server who is in the room every 30 seconds (`resyncMs`).

**Not covered yet:** screen sharing needs the result of the screen capture permission (pass `screenCapture` to `create`) and a foreground service of type `mediaProjection` of your own. Ringing a phone that is closed is done with push: see `POST /v1/calls`.

**Tested** on an Android emulator against the real gateway and media server with a Chrome on the other side (`CallE2eTest`, run by `media.native.spec.ts` in the backend), and on the JVM against the real mediasoup-client's output.

## Tests

```
./gradlew :core:test
```

## Live templates

A Zapiack push template with a timer, progress bar, counter or badge is drawn by the library in the notification itself. The
countdown is the system's own timer, so it keeps ticking on the lock screen with no further push, and when it ends the notification
is drawn once more in the state the template asked for (expired text, no timer, or zeroes). Nothing to set up: it works with the
service this library already adds. Ticking needs Android 7 or later; before that the time left is shown as text.

A template laid out in the builder's canvas is drawn as designed: its elements in their places and sizes, its buttons where they
were put (and they work). Sizes are exact on Android 12 and later; before that elements take the height their content needs.

## Licence of the call code

MIT, including code derived from mediasoup-client (ISC) and sdp-transform (MIT): see `NOTICE`.
