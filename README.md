# Zapiack for Android

The one Zapiack library for Android apps. Push notifications are in it today; new Zapiack products arrive in the same library.

Kotlin library. Android 5.0 (API 21) and later. Works from Kotlin and Java.

## Install

```kotlin
dependencies {
    implementation("com.zapiack:zapiack-android:0.1.0")
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

## Tests

```
./gradlew :core:test
```
