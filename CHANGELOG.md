# Changelog

All notable changes to Zapiack for Android are listed here. Versions follow [semantic versioning](https://semver.org).

## 0.2.0

- Calls: `ZapiackCalls.create` and `ZapiackCall`, voice and video for one-to-one and group calls: microphone, camera (front and back), screen sharing, mute, speaker, and recovery when the phone changes network. The call protocol client is Zapiack's own (in `zapiack-realtime`); the engine is `io.github.webrtc-sdk:android`, now a dependency of `zapiack-android`.
- Realtime: `ZapiackRealtime.request` asks a Zapiack service something over the connection, `RealtimeException.reason` carries the service's word for a refusal, and `addStateListener` adds a listener without taking `onState`.
- Realtime: the new `zapiack-realtime` module (brought in by `zapiack-android`): live messages over one WebSocket with channels, presence, signals, resume after a dropped connection.
- Designed layouts: a template laid out in the builder is drawn as designed, with its buttons where they were placed.
- Live templates: timers count down in the notification by themselves, with progress bars, counters and badges, and an ended state when the timer runs out.

## 0.1.0

First release.

Push for Android apps (Kotlin and Java): registers the device, shows pictures, buttons and styled notifications (colour, thumbnail, shine), reports arrivals and taps.
