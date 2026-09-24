# Call Touch Lock

[![Release](https://github.com/alexbeatnik/CallTouchLock/actions/workflows/release.yml/badge.svg)](https://github.com/alexbeatnik/CallTouchLock/actions/workflows/release.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Stops cheek and pocket taps from hitting the call screen on keypad Android phones — built for the
**Rongyue E5** (Android 13, dual SIM). While a call is active the screen ignores touch, the call
screen stays visible, and every hardware key (red end-call key, volume, keypad) keeps working.

Sibling apps: **[JoyAmp](https://github.com/alexbeatnik/JoyAmp)** (music) and
**[JoyBook](https://github.com/alexbeatnik/JoyBook)** (audiobooks).

## How it works

- A foreground service watches the call state. The main signal is the audio mode
  (`MODE_IN_CALL`), which covers **both SIMs** and needs no permission; the telephony callback is
  used on top when the phone permission is granted.
- While a call is dialing / active, a transparent, non-focusable overlay swallows all touch input.
  It never covers a **ringing** call (also not a second call during a call), so you can answer.
- A watchdog re-checks the real call state every few seconds while the shield is up, so it can't
  get stuck after a call.
- Comes back by itself after a reboot and after the app is updated.
- **Test for 5 seconds** blocks touch right away so you can check it without a call.
- No internet permission, no analytics.

## Permissions

| Permission | Why |
|------------|-----|
| Display over other apps | Required — draws the touch shield over the call screen |
| Phone state | Optional — lets a second call ringing during a call be answered by touch |
| Notifications / foreground service | Keeps the watcher alive |
| Start at boot | Restarts the watcher after a reboot |

## Setup

1. Download `CallTouchLock-x.y.z.apk` from
   [Releases](https://github.com/alexbeatnik/CallTouchLock/releases) and install it.
2. Open the app, turn **Protection** on and allow **Display over other apps**.
3. Use **Test for 5 seconds** to see that taps are ignored.

## Battery Saver on Unisoc phones

With **Battery Saver on**, this Unisoc firmware force-stops background apps soon after the screen
locks ("close app after screen lock") — Call Touch Lock included, so the next call would not be
protected. Keep Battery Saver off; the app shows a red warning while it is on.

## Build

Requirements: JDK 17+, Android SDK (compileSdk 34). Then:

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Releases

Push a tag such as `v1.2.0`, or run **Actions → Release → Run workflow** and enter the version.
The [release workflow](.github/workflows/release.yml) builds `assembleRelease`, derives the
version code from the version (`1.2.3` → `10203`) and publishes `CallTouchLock-1.2.0.apk` plus
`SHA256SUMS.txt` as a GitHub release. Versions with a suffix (`1.2.0-beta1`) become pre-releases.

### Signing

Android installs an update only if it is signed with the same key as the installed copy, so add
these repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|--------|-------|
| `SIGNING_KEYSTORE_BASE64` | The keystore file, base64-encoded |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

Encode the keystore with `base64 -w0 release.jks` (Linux) or
`[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks"))` (PowerShell).
Without these secrets the workflow still publishes an APK, signed with a throwaway debug key
(fine for a fresh install, but it cannot update an existing one).

## License

Copyright 2026 Oleksii Poliakov

Licensed under the [Apache License, Version 2.0](LICENSE).
