# Changelog

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Versions match `versionName` in `app/build.gradle.kts`.

## [1.7.7] — 2026-10-13

**Improved**

- **"Show all / Collapse" now looks like a button**: it used to be a plain line of text
  styled like the body copy, so it was not obvious it was tappable. It is now a small
  **rounded chip with a tinted background**, matching the tag chips, and the tap target
  is larger too

## [1.7.6] — 2026-10-13

**Added**

- **Daily digest** (needs server **1.3.9+**; older servers are unaffected)
  - Once a day at the time you set, the server summarises the last 24 hours of
    messages into a single message. Its title carries the date, in the **time zone
    you picked on the server** — not your phone's
  - A **"Today's digest"** entry appears at the top of the Channels section in the
    drawer: shown when the digest is on, hidden when off. The container channel
    itself stays out of your channel list (it is a system channel only you subscribe to)
  - Digest messages **ignore the local do-not-disturb "silent" rule** — a digest
    exists to be read later, so muting it defeats the purpose
  - Servers without `/user/digest` are treated as "off": behaviour is exactly as before

**Notes**
- The digest switch, time, time zone and priority are all configured in the server's
  web UI; the app only reads the state

## [1.7.5] — 2026-10-12

**Fixed**

- **Icons showing the wrong image**: a cached icon could turn into another app's or channel's
  image (e.g. the Jellyfin channel showing qBittorrent's icon), and only **killing the app
  process** restored it.
  Cause: the bitmaps Glide hands us come from its BitmapPool, and `asImageBitmap()` only
  **wraps the reference without copying pixels**; once an icon scrolled off screen and the
  target was `Glide.clear()`ed, that same memory got **reused** by the next load — so the
  pixels behind a cached image were overwritten by whatever loaded next.
  All bitmaps are now `copy()`d before they are cached, cutting the link to the BitmapPool
  (channel/app icons and message covers in `ui/message`, drawer icons in `ui/drawer`,
  image preview in `ui/preview`).

**Changed**

- **Icons are no longer force-cropped to a circle**: channel / app icons keep their own aspect
  ratio (6dp rounded corners + `ContentScale.Fit`) instead of losing their corners.
  The default letter avatar stays circular (there is nothing to crop there)
## [1.7.4] — 2026-10-12

**Added**

- **Deep-link button** in messages: when a message carries `extras.url` (e.g. the Jellyfin
  plugin's detail page), both the card and the notification gain an "Open" button that jumps
  straight to the target page
  - The Jellyfin app is launched when it can handle it (Android TV supports
    `ACTION_VIEW` + itemId to open an item directly); otherwise the browser opens the web
    detail page (the Jellyfin phone app has no deep-link support yet)
  - When the target has been deleted (`extras.urlGone` is set by the server) the button is
    greyed out, struck through and not clickable

**Fixed**

- Covers were missing once "cache cover images" was enabled: the cover URL is **relative**
  (`/attachments/cached-…`) while the image pipeline only accepted `http` URLs, so relative
  ones were dropped. They are now resolved against the server address (new
  `utils/MessageImage`), covering cards, notifications and the home-screen widget; a failed
  load is retried once and logged
- Image-as-background: the card went fully black until the image loaded
  - The background style is now applied **only once the bitmap is actually available**, with
    the background colour, scrim and text colours fading in together (400 ms); while loading
    or after a failure the normal card style is kept
- Expanding/collapsing an aggregated block and "show full text" changed the height in a
  single frame ⇒ both now animate (220 ms accordion for the "× N" block, 200 ms
  `animateContentSize` for the body text)

**Changed**

- Children of an aggregated card no longer re-render a cover identical to the main card's
  (every episode of a series shares the same poster, and a column of them made the card
  very long); when covers differ they are still shown per child

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
