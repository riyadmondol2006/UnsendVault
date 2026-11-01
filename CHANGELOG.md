# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Releases are built and published automatically from `main` and numbered `MAJOR.MINOR.N`, where `N` is the commit count
(see [docs/RELEASING.md](docs/RELEASING.md)); each GitHub release also lists the commits it contains.

## 1.0.33 (2026-11-01)

Proposed by @Zokii0 in [issue #3](https://github.com/riyadmondol2006/UnsendVault/issues/3).

### Added
- **Apps to save** (Settings > Capture): "All chat apps" (default, unchanged: any chat-style app except excluded ones)
  or "Only selected apps". In selected mode only ticked apps are read at all; Excluded apps is hidden because it isn't
  needed. The first switch pre-ticks the apps already in the archive and the known chat apps installed, so capture
  doesn't silently stop; later switches keep the earlier choice. Apps that send chat-style notifications while not
  ticked are listed as **New** at the top of Selected apps (package names only, at most 20) and can be added in one
  tap. Unticking an app with saved messages offers to delete them.

### Changed
- The capture status card on the archive is shown only when capture needs attention (no access, paused,
  disconnected), and no longer repeats the message/conversation/app counts.
- The app filter chips stay visible after picking an app from the Apps tab, even when the archive has one app; Search
  shows its app chips from one app on.
- App lists (Excluded / Selected apps): shown once the installed apps are loaded, so the list no longer opens
  scrolled down; ticked apps (and new ones) come first and stay in place while you flip switches; a recycled row no
  longer animates its switch from another app's state; apps without a launcher entry show their name.

## 1.0.31 (2026-11-01)

### Fixed
- Apps installed only in a **work profile** or another profile (cloned apps / Dual Messenger, Secure Folder) are now
  found: they appear in Excluded apps (marked "Work profile", "Cloned app" or "Other profile"; apps in both profiles
  say "Also in work profile"), the archive shows their real name and their badged icon instead of the package name and
  a letter, and a chat's "Open app" button opens the app in that profile. Uses `LauncherApps`, no new permission.
  Reported with a proposed fix by @Zokii0 in [issue #3](https://github.com/riyadmondol2006/UnsendVault/issues/3).

## 1.0.29 (2026-11-01)

Requested in [issue #3](https://github.com/riyadmondol2006/UnsendVault/issues/3).

### Added
- Reply from a chat: when the chat app's notification for that chat is still showing and offers a reply button,
  UnsendVault shows a reply box that sends through that action (as a watch or car reply would). It picks only a
  notification that belongs to exactly this chat. When no such notification is showing, the chat explains why and
  offers to open the chat app.
- Optional "messages saved" notifications (Settings > Notifications, off by default): one silent notification per
  chat with the count, grouped; tap to open the chat. Names and text only with a second opt-in, never on the lock
  screen, never while the app lock is on. Not posted for catch-up after a reboot, old history, your own messages or
  the chat you have open. Adds the `POST_NOTIFICATIONS` permission (asked for on Android 13+ when you turn it on).
- "Open on" setting (Settings > Appearance): start the archive on Recent, Chats or Apps.

## 1.0.27 (2026-10-30)

### Added
- Optional update check: after setup the app asks once whether it may check GitHub for new releases. If allowed, it
  asks `api.github.com` at most once a day while the app is open and shows a dialog with Download / Release page /
  Later / Skip this version. "Check now" in Settings > Updates. This adds the `INTERNET` permission, used only for
  this request; nothing from the archive is ever sent.
- README logo, screenshots and badges.

### Changed
- Smaller download: the release APK is about 3.1 MB instead of 11.5 MB (SQLCipher removed, English-only resources,
  compressed native libraries, v2/v3-only signing, narrower R8 keep rules). See docs/APK_SIZE.md.
- Lower background cost: notifications that will be dropped are rejected before anything is copied, bursts of updates
  to one notification are processed once, pausing capture unbinds the listener, reconnect catch-up and rebind requests
  are throttled, and the process start for the listener no longer touches the database, DataStore or WorkManager.
  See docs/BATTERY.md.
- Storage encryption: SQLCipher is no longer used (about 1 MB smaller download). The archive is a regular Room/SQLite
  database in which message text, sender names, conversation titles and keys, edit history, deletion markers and the
  latest-message preview are each encrypted with AES-256-GCM, using keys derived (HKDF-SHA256) from the same
  Keystore-protected secret as before. Lookups that need equality use keyed HMAC blind indexes instead of plaintext.
  App names, package names, timestamps, flags, status and message counts are not encrypted by the app; Android's
  file-based encryption still protects all app storage at rest.
- Search no longer uses a full-text index (it would have stored the words in plaintext). It decrypts and matches
  messages newest first, after the app/conversation/date/favorite filters; matching is case-insensitive and ignores
  accents, and each word may match the message, the sender or the conversation title.
- Database schema version 3 (`vault-v3.db`), with a migration from a plaintext version 2 database.

### Removed
- SQLCipher for Android and its native library.

### Upgrade note
- **The archive from version 1.0.15 cannot be read by this version** (it is a SQLCipher file). On first start it is
  deleted and a new, empty archive is created with the same key; a one-time notice explains this. If you exported a
  backup with 1.0.15, import it after updating: backups use an unchanged format and import normally.

## 1.0.15 (first release)

First version, in development.

### Added
- Capture of message notifications through Notification Access: MessagingStyle, inbox and big-text notifications,
  with adapters for common messaging apps and a generic fallback
- Encrypted archive (Room on SQLCipher, key protected by the Android Keystore) with conversations, full-text search,
  favorites and update history
- "Possibly deleted" label only when the source app displayed an explicit deletion marker matched to exactly one
  message; notification removals recorded for transparency only
- Pause/resume, excluded apps, messages-only mode, one-time-code and sensitive-number filter
- Auto-delete with a daily background job (favorites kept; 0 = keep forever)
- App lock (biometrics or screen lock), relock timeout, secure screen option
- Password-encrypted export and import
- Five Glance widgets (archive count, recent, favorites, app summary, capture control), counts and app names only by default,
  refreshed only on change
- No INTERNET permission, enforced at build time and checked on every release APK
- CI: unit tests, lint, permission guard, signed GitHub Releases with SHA-256 checksums and signing-certificate
  fingerprint
