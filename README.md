# NidaSafe

Android offline safety monitor for accidental media sound leakage. NidaPlayer is **not modified**.

## v0.2.0 — Speaker Guard

- A new **スピーカー再生を禁止** switch persists in local SharedPreferences.
- Turning the switch ON starts NidaSafe's user-initiated foreground monitor.
- When the media route is not a recognized personal listening device, NidaSafe repeatedly sets **media volume to zero** and asks the active media player to pause.
- Wired headsets, USB DAC / USB headsets and Bluetooth audio are allowed. On Android 13+ it checks the predicted **media routing destination**, not just whether a headset is connected.
- Prior protections remain: a headset disconnect triggers volume=0 and a pause request; rapid volume increases are limited; with Speaker Guard OFF, built-in speaker media volume is capped at 20%.
- The app intentionally never restores volume or auto-resumes playback. Notifications, alarms and calls use their own streams and are not deliberately changed.
- No INTERNET permission, account or remote telemetry.

## Build / install

GitHub Actions builds an installable debug APK for pull requests, main pushes, or workflow dispatch. Go to **Actions → NidaSafe Android Debug APK → latest successful run → Artifacts → NidaSafe-debug-APK**. Install over the previous version (same applicationId, but an APK from a different signing key may require uninstalling first).

NidaSafe itself must be opened and its protection service started once by the user. Speaker Guard is **OFF by default** to avoid unexpectedly muting other apps. Enable its switch to start protection immediately. Foreground service runs with a persistent notification and can be stopped in the app.

## Android restrictions and limits

- This is **best-effort**, not a guaranteed OS-level speaker blocker. Apps may ignore pause events and sound may leak briefly during output handover.
- On Android 13+, `AudioManager.getAudioDevicesForAttributes()` reports the *predicted* media route; an individual app may override its actual route. Older Android versions use a connected-device fallback.
- Bluetooth A2DP speakers and Bluetooth headphones may be indistinguishable, so A2DP is treated as an allowed route. If you need to mute every external loudspeaker as well, Android's ordinary audio APIs cannot ensure that.
- Media volume changes are shared with all media apps, not only NidaPlayer. Volume remains zero on headphone reconnect until the user changes it.
- Battery policies may stop background services; actual operation should be checked on the target Pixel 6 / Android 17.
- Foreground service uses Android's `specialUse` type because NidaSafe does not play media itself. Google Play publication of that type requires reviewing its declared purpose.

## Manual checks

1. Start protection with no headset, enable Speaker Guard, attempt playback in NidaPlayer and YouTube: media volume should remain zero.
2. Connect USB-C DAC / wired headphones / Bluetooth audio: playback is allowed after manually raising media volume.
3. Disconnect headset during playback: media volume should drop to zero and media pause should be requested.
4. Connect headphones while selecting phone speaker as the media route: on Android 13+, NidaSafe should mute the media route.
5. Turn Speaker Guard OFF: 20% speaker limit remains; alarms/notification volume controls are unaffected.
