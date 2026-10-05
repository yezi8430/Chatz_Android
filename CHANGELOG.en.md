# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions match `versionName` in `app/build.gradle.kts`.

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
