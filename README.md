<p align="center">
  <img src="docs/assets/logo.svg" width="128" height="128" alt="UnsendVault app icon: a chat bubble inside a vault door">
</p>

<h1 align="center">UnsendVault</h1>

<p align="center">
  <b>Read deleted and unsent messages from WhatsApp, Instagram, Messenger and Telegram notifications.</b><br>
  Offline, encrypted, open-source Android app. No ads, no trackers, no account.
</p>

<p align="center">
  <a href="https://github.com/riyadmondol2006/UnsendVault/releases/latest"><img src="https://img.shields.io/github/v/release/riyadmondol2006/UnsendVault?label=download&amp;color=1B3A5C" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-7FD1C7" alt="Android 8.0 or newer">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/riyadmondol2006/UnsendVault?color=1B3A5C" alt="License: Apache-2.0"></a>
  <a href="https://github.com/riyadmondol2006/UnsendVault/actions/workflows/ci.yml"><img src="https://github.com/riyadmondol2006/UnsendVault/actions/workflows/ci.yml/badge.svg" alt="CI status"></a>
</p>

An offline, encrypted archive of the message previews your messaging apps show in notifications. If someone deletes
or unsends a message after its notification reached your phone, you can still read what the notification said.

Android app, open source (Apache-2.0). No ads, no trackers, no analytics, no account. Works fully offline; the only
network use is an optional update check that you have to allow first.

## Read this first: what it can and cannot do

UnsendVault works only through Android's **Notification Access**, which you grant in system settings. It keeps a copy
of the **text that notifications showed**, on your phone, from the moment you grant access.

It **can**:

- archive message previews from WhatsApp, Instagram, Messenger, Telegram, Signal, Google Messages and other apps that
  post message notifications, grouped by app and conversation, searchable. (Verified on a Pixel 6 / Android 17 with
  Google Messages SMS and synthetic MessagingStyle notifications; the third-party messenger adapters are so far
  covered by unit tests only. See [docs/TEST_REPORT.md](docs/TEST_REPORT.md).);
- keep earlier versions when a notification's text for the same message changes ("updated");
- mark a message **possibly deleted** when the source app itself showed a marker such as "This message was deleted"
  or "unsent a message" and it matches exactly one archived message. The marker is shown as the evidence.

It **cannot**:

- recover anything from before you granted access, or from while capture was paused or stopped;
- read chats you have **open on screen** (apps usually post no notification for them), chats you **muted** if the app
  posts nothing for them, or apps where **previews are hidden** ("New message");
- save **photos, videos, voice notes or files**; you get a placeholder such as "Photo";
- read content Android withholds from apps like this one: Android 15+ may hide one-time codes (in our test it did
  not, which is why UnsendVault's own code filter is on by default), and Android 17+ may withhold end-to-end
  encrypted messages (Google lists "Restricted message access" without documenting the details; see
  [docs/PRODUCT_RESEARCH.md](docs/PRODUCT_RESEARCH.md#54-android-17-api-37-message-access-restrictions));
- tell whether a message was deleted just because its notification disappeared. Dismissing or reading a message also
  removes notifications, so that is never treated as deletion;
- fetch anything from a server, a chat backup or another device. It is not a recovery tool and not a monitoring tool.
- reply once a chat's notification is gone. Replying from UnsendVault works only through the chat app's own
  notification reply button, while that notification is still showing; UnsendVault never connects to a chat service.

Some phone makers stop background apps aggressively. If capture stops, the app shows it and helps you reconnect;
see [Reliability](#reliability).

## Features

- Archive by app, conversation and message, newest first; full-text search with app and date filters
- Reply from a chat in UnsendVault through the chat app's own notification reply action, while that notification is
  showing (WhatsApp, WhatsApp Business, Viber, Telegram and others that offer a reply button). Otherwise an
  "Open app" button takes you to the chat app
- Optional "messages saved" notification per chat (off by default, silent); tap it to open the chat. Names and
  text only if you allow it, never on the lock screen and never while the app lock is on
- Choose which tab the archive opens on: Recent, Chats or Apps
- Message detail with update history, capture times, and deletion evidence when there is any
- Favorites (kept by auto-delete)
- Pause and resume capture; save all chat apps except excluded ones, or only the apps you select (new chat apps are
  offered with one tap); work-profile and other-profile apps included; "messages only" mode (default on)
- One-time-code and sensitive-number filter before anything is stored (default on)
- Auto-delete after N days (default 30; 0 keeps forever), run by a daily background job
- App lock with fingerprint, face or screen lock; relock timeout; hide content in Recents and block screenshots
- Encrypted export and import (password-based) to a file you choose
- Five home-screen widgets: archive count, recent, favorites, app summary, capture control. They show counts and
  app names only unless you turn on content, and never content while the app lock is on
- Material 3, light/dark/AMOLED, dynamic color on Android 12+

Requires Android 8.0 (API 26) or newer.

## Screenshots

Synthetic demo data only.

| Archive | Conversation | Possibly deleted |
|---|---|---|
| <img src="docs/screenshots/01-archive-chats.png" width="240" alt="Archive with conversations from several apps"> | <img src="docs/screenshots/02-conversation.png" width="240" alt="Conversation timeline"> | <img src="docs/screenshots/03-possibly-deleted-detail.png" width="240" alt="Message marked possibly deleted with the app's own marker as evidence"> |

| Update history | Search | Settings |
|---|---|---|
| <img src="docs/screenshots/04-update-history.png" width="240" alt="Message with an earlier version kept"> | <img src="docs/screenshots/05-search.png" width="240" alt="Search with filters"> | <img src="docs/screenshots/06-settings.png" width="240" alt="Settings"> |

## Install

1. Download `UnsendVault-<version>.apk` from the [latest release](https://github.com/riyadmondol2006/UnsendVault/releases/latest).
2. Verify it (below).
3. Open it on your phone and allow installing from your browser or file manager when asked.
4. Open UnsendVault and follow the setup. On **Android 13 and later**, Android may block Notification Access for apps
   installed from a file. If the switch is greyed out: Settings > Apps > UnsendVault > ⋮ (top right) >
   **Allow restricted settings**, then grant Notification Access again.

UnsendVault is not on Google Play. Updates are new APKs on the Releases page, signed with the same key; Android
refuses an update signed by anyone else.

### Verify the download

Each release lists the APK's SHA-256 and the signing certificate's SHA-256, and ships `UnsendVault-<version>.apk.sha256`.

```sh
sha256sum --check UnsendVault-1.0.57.apk.sha256           # UnsendVault-1.0.57.apk: OK
apksigner verify --print-certs UnsendVault-1.0.57.apk     # compare "certificate SHA-256 digest" with the release notes
```

Official signing certificate (SHA-256):

```
e4:43:c8:69:aa:cc:20:5d:6c:41:c9:17:f9:4a:6a:83:be:4c:00:53:c2:4b:0d:24:6c:c7:74:ff:75:13:51:97
```

`apksigner` is part of the Android SDK build-tools. The signing certificate is the same for every release; if a
release ever shows a different one, do not install it and open an issue. Details: [docs/RELEASING.md](docs/RELEASING.md#how-users-verify-a-download).

## Privacy in short

- Everything stays on your phone. The only network request the app can make is an **opt-in update check** to
  `api.github.com` (at most once a day while the app is open, never in the background, nothing from your archive is
  sent; see [docs/UPDATES.md](docs/UPDATES.md)). Network code is confined to one package by a test, the build fails if
  any permission outside a short allowlist appears, and every release APK is checked again before publishing.
- Message text, sender names and conversation titles are encrypted in the archive (AES-256-GCM, per field) with a key
  protected by the Android Keystore that cannot leave the device, so Android cloud backup and device transfer are
  disabled. App names, times and message counts are not encrypted by the app (Android's own storage encryption still
  covers them). Use **Export** to make an encrypted backup file.
- Updating from 1.0.15: that version's archive format can't be read any more, so it is replaced by a new, empty
  archive. Export a backup in 1.0.15 first, then import it after updating.
- Nothing is sent to the developer or anyone else: no analytics, crash reporting, ads or third-party SDKs.
- The archive contains messages other people sent you. Please respect that: keep retention short, exclude apps you do
  not need, and follow the law where you live.

Full details: [PRIVACY.md](PRIVACY.md). Security reports: [SECURITY.md](SECURITY.md).

## Permissions

| Permission | Why |
|---|---|
| Notification Access (granted by you in system settings) | Read message notifications |
| `INTERNET` | Only the opt-in update check to `api.github.com` |
| `POST_NOTIFICATIONS` | Only the optional "messages saved" notifications (off by default; Android 13+ asks you first) |
| `USE_BIOMETRIC` / `USE_FINGERPRINT` | App lock |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | Used by WorkManager for the daily auto-delete job and widget updates; reschedules after reboot |

That is the complete list (plus an internal, non-exported broadcast permission that AndroidX adds).

## Reliability

- The app and the capture widget show whether capture is running, paused, needs access, or was disconnected.
- If it was disconnected, opening the app asks Android to reconnect the listener.
- On phones that stop background apps (see [dontkillmyapp.com](https://dontkillmyapp.com/)), set UnsendVault's battery
  usage to "Unrestricted" / "Don't optimize" and allow autostart if your phone has that option. The status screen
  links to the right settings page.
- No foreground service, no exact alarms, no polling: widgets and lists update only when something changes.

## Build from source

Requirements: JDK 21 (bytecode targets 17), Android SDK with platform 37 (`platforms;android-37.0`) and build-tools
37.0.0. Gradle 9.8.2 comes through the wrapper.

```sh
git clone https://github.com/riyadmondol2006/UnsendVault.git
cd UnsendVault
./gradlew :app:assembleDebug                 # app/build/outputs/apk/debug/app-debug.apk (id com.riyadm.unsendvault.debug)
./gradlew :app:testDebugUnitTest :app:lintDebug :app:verifyPermissionsDebug
./gradlew -q :app:printVersion
```

A signed release build needs your own key (`keystore.properties` or the `SIGNING_*` environment variables); see
[docs/RELEASING.md](docs/RELEASING.md). Builds made with a different key cannot update an installed official release.

## Documentation

- [docs/PRODUCT_RESEARCH.md](docs/PRODUCT_RESEARCH.md): scope, platform research with sources, risks
- [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md): architecture, data flow, algorithms, security, testing
- [docs/WIDGET_DESIGN.md](docs/WIDGET_DESIGN.md): widgets
- [docs/RELEASING.md](docs/RELEASING.md): CI, signing, versions, verification
- [docs/TEST_REPORT.md](docs/TEST_REPORT.md): what was tested on a real device, defects found, and what was not tested
- [CONTRIBUTING.md](CONTRIBUTING.md), [CHANGELOG.md](CHANGELOG.md)

## License

Copyright 2026 riyadmondol2006. Licensed under the [Apache License 2.0](LICENSE). Third-party components and their
licenses are listed in [NOTICE](NOTICE).

UnsendVault is an independent project and is not affiliated with WhatsApp, Instagram, Messenger, Meta, Telegram,
Signal, Google or any other messaging service. Product names are used only to describe compatibility.
