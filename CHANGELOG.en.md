# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions match `versionName` in `app/build.gradle.kts`.

## [1.7.3] — 2026-10-06

**Fixed**

- Stuck on "no network" for 20-30 seconds after toggling airplane mode (with Wi-Fi staying on)
  - "Is there a network" no longer depends on `NET_CAPABILITY_VALIDATED`. That flag is the
    captive-portal probe result, which needs to reach Google's connectivity check endpoint —
    often never set on mainland-China / LAN-only setups, and always false while the system
    re-probes after an airplane-mode toggle
  - When the network flips back from down to up, the old connection is discarded before
    reconnecting (a half-dead socket keeps reporting "connected", which used to block the
    rebuild and left only OkHttp's 30s ping to notice it)
  - After a network loss the app now actively re-checks the system state (at 2/5/12/27s),
    in case `onLost` arrived but the restore callback never did

## [1.7.2] — 2026-10-05

First public release. Everything currently in the codebase:

**Multiple servers**

- Each server is a "card" you can enable or pause independently
- Server type is auto-detected on add (`/config` probe decides whether it is Chatz; otherwise Gotify)
- Custom display name; automatic keep-alive interval (45s on Wi-Fi / 180s on mobile)

**Channels (Chatz)**

- Discover, subscribe, unsubscribe
- Channel password and channel icon
- Per-channel do-not-disturb

**Messages**

- Markdown rendering, image and attachment preview
- Read / unread, mark all read, archive / unarchive
- Full-text search (Room FTS), paged loading (Paging 3)
- Aggregation and collapsing, tags, sender role badge
- Inline reply, draft retention, bubble notifications

**Do not disturb**

- Global switch plus a time window (start/end time, weekdays)
- Per-app rules, or scoped to a single channel

**More**

- Home screen widget (recent messages)
- Custom theme color, dark mode
- Auto-start on boot, foreground service keep-alive, automatic reconnect on network change

**Security**

- Server addresses and credentials now encrypted with Android Keystore + AES‑256‑GCM
- `allowBackup` disabled — credentials never land in cloud backups

> Changes before this release were not tracked separately; maintenance starts at 1.7.2.
