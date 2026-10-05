# Privacy

## In one line

**This app collects nothing.** No analytics, no crash reporting, no telemetry SDK. It only talks to the servers you configure yourself.

## Where data lives

| Data | Location | Notes |
|---|---|---|
| Message bodies, channels, read state | Local Room database | Never uploaded; gone when you uninstall |
| Server address, tokens, ntfy username/password | `servers_json` in `SharedPreferences` | AES‑256‑GCM encrypted, key in the Android Keystore |
| Theme color, do-not-disturb settings, drafts | `SharedPreferences` | Non-sensitive, stored in cleartext |

Credential encryption uses a device-bound Keystore key that **cannot be exported**. This protects data at rest:

- Stops: `adb backup` exports, reading the data directory on a rooted device, forensic images (all yield ciphertext, no key)
- Does not stop: runtime attacks while the device is unlocked (anything running as this app can decrypt)

`android:allowBackup="false"` is also set, so credentials are excluded from Android's automatic and cloud backups.

## Network

The app only sends requests to the addresses you entered under "Add a server", in order to:

- Pull and push messages
- Detect the server type (a `/config` request)
- Download images, attachments and channel icons referenced by messages

There is no other network destination.

## Permissions

Only what receiving messages requires — see the Permissions section in [README.en.md](README.en.md). No location, contacts, call log, SMS, camera or microphone permission.

## Third-party libraries

No analytics, advertising, or crash-reporting libraries among the dependencies. The third-party libraries used (OkHttp, Retrofit, Glide, Markwon, Room, Compose, …) are for local data handling and UI only.

## If you want to verify it yourself

- Capture the traffic: only your own servers show up
- Decompile the APK and look for tracking calls (it is v2-signed, with code shrinking and resource shrinking enabled)
