# NidaSafe

Android app prototype focused on preventing accidental audio leakage when headphones disconnect.

## What it does
- Monitors Android audio output-device changes while its foreground protection service is running.
- When a recognized wired, USB, or Bluetooth headset disconnects and no recognized headset output remains, sets media volume to zero first and sends a media-pause key event.
- Shows an ongoing notification while protection is active.

## Build
GitHub Actions builds a debug APK on pushes to `main` and manual workflow dispatch. Download the APK from the workflow run's Artifacts section.

## Important limitations
- Android and individual media apps can ignore global media-pause events; playback stopping is best-effort.
- Test with the actual Pixel 6, wired and Bluetooth headsets, and YouTube before relying on it in public.
- The app does not automatically restore volume or resume playback.
