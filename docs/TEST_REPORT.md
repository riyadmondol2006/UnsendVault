# UnsendVault test report

Results recorded on 2026-10-29 by riyadmondol2006. Everything below was actually executed; anything that was not run
is listed under [Not tested](#not-tested). All message content used for testing and screenshots is synthetic
(fictional names such as "Alex Rivera", "Project Team").

## Test device

| | |
|---|---|
| Device | Google Pixel 6 (`oriole`) |
| Android | 17 (API 37), build `CP3A.261005.002.A1`, user/release-keys |
| Notes | Rooted (KernelSU), so `adb shell` runs as uid 0. Synthetic notifications were therefore posted as uid 2000 (`su 2000 -c "cmd notification post …"`), i.e. from the real `com.android.shell` package, through the normal NotificationManager → listener path. The device has a screen-lock credential (not known to the tester). |
| Messengers installed | Google Messages only. WhatsApp, Instagram and Messenger were **not** installed. |

The request mentioned a Pixel 6 Pro; the connected device reports itself as a Pixel 6 (`oriole`).

> **Note (storage change after these results).** SQLCipher was removed after the results below were recorded and
> replaced by field-level encryption on the framework SQLite (schema v3; see CHANGELOG "Unreleased" and
> IMPLEMENTATION_PLAN sections 8-9). The recorded results are left unchanged. Items that now differ: the instrumented
> SQLCipher tests (migration on SQLCipher, "no `SQLite format 3` header", wrong key fails to open) no longer exist; they
> were replaced by `FieldEncryptionDeviceTest` (no plaintext in db/-wal/-shm, WAL active, reopen with the Keystore) and
> by 1→2→3 and 2→3 `MigrationTestHelper` runs. Search no longer uses FTS + LIKE but decrypts and matches rows. The
> database file now *does* start with `SQLite format 3` (only the content columns are encrypted). The release smoke
> test's "SQLCipher DB" line, the cold-start time and the APK size were measured with SQLCipher and need re-measuring.
> A 1.0.15 archive is not migrated (it is replaced by an empty vault with a notice). Device results for the new code
> are in [Round 2](#round-2-2026-10-30-field-encryption-apk-size-battery-update-check) below.

## Automated tests

| Suite | Where | Result |
|---|---|---|
| JVM + Robolectric unit tests (`:app:testDebugUnitTest`) | Linux host, JDK 25, Robolectric SDK 26/30/34 | **282 run, 0 failed, 1 skipped** (an API 28+ `Person` test that cannot run on SDK 26) |
| Instrumented tests (`:app:connectedDebugAndroidTest`) | Pixel 6, Android 17 | **23 run, 0 failed** |
| Lint (`:app:lintDebug`) | host | 0 errors, 15 warnings (all `UnusedAttribute`: attributes ignored on older API levels) |
| Offline/permission gate (`:app:verifyPermissionsDebug`, `…Release`) | host | pass: no INTERNET; only USE_BIOMETRIC, USE_FINGERPRINT, WAKE_LOCK, RECEIVE_BOOT_COMPLETED and the app-private dynamic-receiver permission |

Unit test areas: generic parser and per-app adapters (WhatsApp, Instagram, Messenger/Facebook, Telegram/Signal,
SMS and others, registry fallback), deletion-marker rules, sensitive/OTP filter, engine pipeline, snapshot building
from real `Notification` objects, removal-reason mapping, listener status; Room ingest/dedupe/update history,
deletion evidence, search (FTS + LIKE), deletes, retention policy and worker, DataStore settings, backup crypto
(round trip, wrong password, tamper, truncation, version), backup round trip through Room, key manager, vault health,
Room migration 1→2 from the exported schema; view models, lock state machine (fake clock), demo data; all five widget
contents and privacy modes, widget loaders, debounced sync engine.

Instrumented test areas (on the Pixel): Room `MigrationTestHelper` 1→2 on framework SQLite and on SQLCipher;
SQLCipher at rest (no `SQLite format 3` header, no plaintext marker in db/-wal/-shm, wrong key fails); Android
Keystore wrap/unwrap/tamper/delete; encrypted vault end to end incl. Keystore-key loss reported and reset recovering;
backup export/import through a real `ContentResolver` file URI; capture of a real MessagingStyle notification object;
access-settings intent resolution; Compose UI (onboarding, lock gate never composes archive content, lock without
device credential offers a safe way out, home empty/paused/populated/app filter, search filtering, message detail
with deletion evidence/removal/revisions/Android-redaction explanation, settings toggles, widget-content toggle
disabled under app lock, export password validation); widget providers registered without polling, no data
observed when no widget is placed.

## Defects found on the device and fixed

| # | Found by | Problem | Fix | Verified |
|---|---|---|---|---|
| 1 | Instrumented `KeystoreAndVaultTest` | StrongBox AES-GCM key generated fine but every decrypt failed with Keymaster `TOO_MANY_OPERATIONS` (reproduced 5/5 runs). Production only worked because the key manager silently fell back to TEE. | Wrapping key now uses the hardware-backed TEE by default (`VaultKeyManager.keyPlacements`). | 3/3 reruns pass; full suite 23/23 |
| 2 | Manual E2E (reposting a conversation) | A poster that re-stamps every message on each update (as `cmd notification post` does) caused the earlier messages to be stored twice. | Identity matches same sender + same text within ±10 min first, never claiming one stored row twice per notification. | Unit regression tests + on device: re-posting 4 messages added only the 1 new one |
| 3 | Manual E2E | Two different messages from one sender sharing a timestamp in one notification were merged into a single message with a false "2 versions" edit. | An edit is inferred only when the (sender, timestamp) slot is unambiguous in both the notification and the archive and the old text is no longer shown. | Unit regression tests + on device: no false versions; the genuine edit in the demo data still shows "Update history" |

## Manual end-to-end tests on the Pixel 6 (Android 17)

| Area | Steps | Result |
|---|---|---|
| Fresh install, onboarding | `pm clear`, launch | 5-step onboarding shows purpose, honest limitations (no server recovery, muted/hidden previews, media, Android 15/17 withholding), privacy, access, optional lock. Pass |
| FLAG_SECURE default | `screencap` of the app with default settings | Fully black capture. Pass |
| Grant Notification Access via system UI | "Grant notification access" → system screen → toggle → system "Allow" dialog | Opened the per-app `NotificationAccessDetailsActivity` directly; after Allow the listener appeared in `enabled_notification_listeners`; the app showed "Notification access is on". Pass |
| Catch-up on connect | Listener connected with notifications already in the shade | Archived what was in the shade, including two real SMS previews from Google Messages (see Android 17 below). They were deleted with the "exclude app → also delete" flow and are not in any screenshot. Pass |
| Capture MessagingStyle group | Shell posts "Project Team" with 2 messages | 2 messages in a group conversation, app label "Shell". Pass |
| Dedupe / repost | Same conversation re-posted with history + 1–2 new messages | Before fix: duplicates and a false edit (defects 2, 3). After fix: only new messages added. Pass |
| Per-app exclusion | Exclude Google Messages, choose "Delete" | Its 2 messages removed; picker lists apps from the archive first, then launcher apps. Pass |
| Capture pause / resume (in app) | Pause, post, resume | Message posted while paused not saved; status card "Capture is paused" with Resume. Pass |
| OTP filter (UnsendVault) | Post "Your verification code is 482913…" | Stored as "One-time code hidden by UnsendVault" ("Hidden by filter"); searching "902211" (another filtered code) gave 0 results. Pass |
| Android OTP redaction | Filter off, `redact_otp_from_untrusted_listeners true` (as root), post OTP | The listener received the code **unredacted**. Platform redaction did not apply in this setup; UnsendVault's own filter is what protects users. Filter re-enabled and re-verified afterwards |
| Notification removal | `cmd notification snooze` the conversation | Detail shows "Notification removed … This does not mean the message was deleted." Status unchanged. Pass |
| Snooze expiry | Snoozed notification came back after 10 min | Re-captured without duplicating rows. Pass |
| Favorites | Favorite a message | Appears in Favorites; survives export/import. Pass |
| Search | "draft" | 1 correct result with filters (app, conversation, dates, favorites, possibly deleted) available. Pass |
| Revoke access | `cmd notification disallow_listener` | Status card: "Capture is off: no notification access" + grant button. Pass |
| Regrant access | `cmd notification allow_listener` | "Capture is active"; new messages captured; a message posted while revoked but still in the shade was archived by the catch-up after regrant (i.e. only after access was granted again). Pass |
| Battery Saver | `cmd power set-mode 1`, unplugged | Message captured. Pass |
| Process killed (OEM/LMK-style) under Battery Saver | `kill -9` the app process, do not open the app, post | System rebound the listener in a new process (pid 8847 → 11888); message captured. Pass. Battery Saver restored to off |
| Reboot | `adb reboot`; post before first unlock; owner unlocked; post again without opening the app | Before unlock: app not running (credential-encrypted storage locked, expected). After unlock: listener process started by the system on its own; both messages archived. Pass |
| Retention job | `dumpsys jobscheduler`; forced run | One WorkManager job `RetentionWorker` with constraints BATTERY_NOT_LOW + IDLE, no network; 0 alarms registered by the app. Forced run: "Retention removed 0 message(s)", result SUCCESS (all data was newer than 30 days; age-based deletion is covered by unit tests). Pass |
| Widgets: picker | Pixel Launcher widget picker | All five widgets listed with descriptions and generated previews (synthetic data). Pass |
| Widget: Archive count | Placed on home screen | Numbers only; updated by itself from 13 to 14 after a capture (event-driven, no polling). Pass |
| Widget: Capture control | Placed; tapped twice | Pause from widget → message not saved; resume → next message saved; TalkBack text "Capture is paused. Double tap to resume." Pass |
| Widget: Recent | Placed (page 2) | Default shows "Shell · 14 recent · 1 min. ago" (no names or text). After enabling "Show message text in widgets" it showed sender + preview within ~4 s; setting turned back off. Pass |
| Widgets: Favorites, App summary | — | Seen in the picker with previews; not placed on the home screen (no free space). Content covered by unit tests |
| Demo data + rendering | Debug "Insert synthetic demo data" | Chats, possibly-deleted evidence ("WhatsApp showed 'This message was deleted' … strong hint, not proof"), update history ("2 versions", earlier version kept), media placeholders, Material You colors. Pass |
| Encrypted export (SAF) | Settings → Export → password → system "Save" picker → Downloads | 1,477-byte `.uvbackup` file; header `UVB`; none of the synthetic strings present; 7.88 bits/byte entropy; "Last export" updated. Pass |
| Import (SAF) wrong password | Import → pick file → wrong password | "Wrong password, or the file isn't an UnsendVault backup." Archive untouched. Pass |
| Import (SAF) after delete-all | Delete all → import with right password | "Imported 18 messages in 6 conversations"; possibly-deleted evidence and favorite preserved. About 5.6 s including UI polling (PBKDF2 310k iterations). Pass |
| App lock enable | Settings → App lock | System BiometricPrompt "Unlock UnsendVault" with fingerprint and "Use PIN". Cancel leaves app lock off. Pass (successful unlock needs the owner's credential; the locked state is covered by the instrumented Compose test) |
| Secure screen toggle | Turn off for screenshots, back on | Screenshots possible only while off; after re-enabling, capture is fully black again. Pass |
| Release build (R8) smoke test | `assembleRelease`, signed locally with a throwaway debug key, installed side by side | Permissions: no INTERNET. Cold start 239 ms. Onboarding, capture, SQLCipher DB, export, and import of the debug build's backup (18 messages merged) all worked with no crash or serialization error. Uninstalled afterwards so it cannot block the real signed release |

## CI and signed release

| Check | Result |
|---|---|
| GitHub Actions "CI and Release" run [40920946782](https://github.com/riyadmondol2006/UnsendVault/actions/runs/40920946782) on commit `0d3c735` | Build, test and lint: success. Sign and publish the release: success (the emulator job runs only on schedule/manual dispatch and was skipped) |
| Release [v1.0.15](https://github.com/riyadmondol2006/UnsendVault/releases/tag/v1.0.15) | Assets: `UnsendVault-1.0.15.apk`, `.apk.sha256`, `SHA256SUMS.txt`, `mapping-1.0.15.zip` |
| Independent check of the downloaded APK | `sha256sum -c` OK; `apksigner verify`: v2 + v3 verified, 1 signer, certificate `CN=Riyad M, OU=riyadmondol2006, C=BD`, SHA-256 `e443c869aacc205d6c41c917f94a6a83be4c0053c24b0d246cc774ff75135197`; versionCode 15 / versionName 1.0.15; permissions: no INTERNET |
| Official APK on the Pixel 6 | Installed, cold start 308 ms, onboarding, capture of a synthetic MessagingStyle message: pass |

### Android 17 observations (this device and build only)

- The framework overlay `com.google.android.overlay.messagingreadrestrictionallowlist` sets
  `config_messaging_read_restriction_allowlist` to `com.google.android.apps.pixel.relationships` and
  `com.google.android.contacts`.
- Despite that, Google Messages SMS notification text reached UnsendVault's listener in full. End-to-end encrypted
  RCS and third-party messengers could not be tested.
- `redact_otp_from_untrusted_listeners true` did not redact an OTP posted by `com.android.shell` before it reached
  the listener.

## Not tested

- **WhatsApp, WhatsApp Business, Instagram, Messenger, Telegram, Signal and other third-party messengers on a real
  device**: none were installed. Their adapters, noise filtering and deletion markers ("This message was deleted",
  "unsent a message") are covered only by unit tests built from synthetic snapshots. Real marker wording, localization
  and notification structure may differ by app version.
- End-to-end encrypted RCS in Google Messages under Android 17's restriction.
- Completing an app-lock unlock with biometrics/PIN on the device, and relock after timeout (owner credential
  required). The lock gate and state machine are covered by instrumented and unit tests.
- Placing the Favorites and App summary widgets on a home screen; OEM launchers (One UI, MIUI, etc.).
- Other devices and Android versions (API 26–36), tablets and foldables, OEM ROMs. The code gates APIs by
  version and the unit tests run on Robolectric SDK 26/30/34, but no physical device other than the Pixel 6 was used.
- Large font scale, RTL and TalkBack walkthroughs on the device.
- Retention actually deleting old messages on the device (all data was newer than the 30-day cutoff); covered by
  unit tests.

## Round 2 (2026-10-30): field encryption, APK size, battery, update check

Same Pixel 6 / Android 17 device. Code: `main` after merging the field-encryption, APK-size, battery and update-check
branches.

### Automated

| Suite | Result |
|---|---|
| Unit tests (`:app:testDebugUnitTest`) | **423 run, 0 failed, 1 skipped** |
| Instrumented tests on the Pixel (`:app:connectedDebugAndroidTest`) | **29 run, 0 failed** (incl. FieldEncryptionDeviceTest, MigrationInstrumentedTest 1→2→3 and 2→3, KeystoreAndVaultTest with the legacy-archive case, UpdateUiTest ×4) |
| Lint | 0 errors, 15 warnings (`UnusedAttribute`) |
| Permission gate (debug + release) | pass; release declares INTERNET (update check), USE_BIOMETRIC, USE_FINGERPRINT, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, app-private dynamic receiver permission; no network-state permissions |
| Release APK size (unsigned, R8) | **3.10 MB** (1.0.15: 11.5 MB) |

### Manual on the device

| Area | Steps | Result |
|---|---|---|
| Upgrade from a SQLCipher-era build | Install a pre-change debug build, capture a message, install the new build over it | Old `unsendvault.db` (random header) detected and removed; `vault-v3.db` created; after onboarding the notice "Your previous archive was removed … import it now" with "Got it". Pass |
| Import a backup made by the official 1.0.15 release | Export from 1.0.15 (6 synthetic messages), import into the new vault | "Imported 6 messages in 4 conversations". Pass |
| No plaintext in the new database | Pull `vault-v3.db` and `-wal` via `run-as`, search for message text, sender names and a conversation title | 0 occurrences of any of them; package names are present in plaintext (documented trade-off). Pass |
| Search over encrypted data | Search "upgrading" | 1 correct result. Pass |
| Pause unbinds the listener | Pause in Settings, inspect `dumpsys notification` live listeners | While paused the debug build's listener is not bound (zero callbacks); after resume it is bound again and a new message is captured. Pass |
| Update check: consent | Fresh onboarding | "Check for updates automatically?" dialog once, after onboarding, with the GitHub/IP disclosure. Pass |
| Update check: popup (real GitHub API) | Debug build reporting version 1.0.1, Settings > Updates > Check now | "UnsendVault 1.0.15 is available · You have 1.0.1-debug" with notes and Download / Release page / Later / Skip this version. Pass |
| Update check: download | Tap Download | Chrome downloaded the release APK (11,511,758 bytes, same as the published asset); test file deleted afterwards. Pass |
| Update check: automatic (release-type build) | R8 build, Allow | "Last checked" set immediately; installed 1.0.25 > 1.0.15 so correctly no popup. Pass |
| R8 release smoke test | Side-by-side `.r8test` copy of the release build, signed with a throwaway key, uninstalled afterwards | Cold start 223 ms; onboarding, capture, conversation and message detail (typed navigation routes), backup import and export (kotlinx.serialization) all work; no crash or serialization error in logcat. Pass |

### Battery measurement method notes

- Debug builds are not representative: a `simpleperf` profile of the debug build during a 1-message-per-second
  workload showed most time in ART's interpreter and ~18 % in JIT compilation, and 13.8 % in Glance's one-time
  widget-picker preview generation (runs once per app version, 15 s after start; `pm clear` re-triggers it). Debug
  before/after numbers were therefore discarded.
- Measurements use warmed-up release builds, our process's CPU time (`utime+stime` from `/proc/<pid>/stat`, 100 Hz
  ticks), and three workloads posted from `com.android.shell`: W1 = 120 non-message notifications (0.5 s apart),
  W2 = 60 chat messages (1 s apart), W3 = 30 bursts of 8 identical reposts.
- Baseline, official 1.0.15 release (two runs): W1 66 / 66, W2 195 / 87, W3 175 / 148 ticks.

### Release v1.0.27 (published by CI)

| Check | Result |
|---|---|
| CI run [40987382178](https://github.com/riyadmondol2006/UnsendVault/actions/runs/40987382178) | Build/test/lint success; sign and publish success. (The previous run, for v1.0.26, signed and verified the APK but the publish step failed because the releases list did not yet show the new draft; the orphaned draft and tag were deleted and the script now retries the lookup.) |
| Downloaded `UnsendVault-1.0.27.apk` | **3,115,478 bytes**; `sha256sum -c` OK; v2 + v3 signature, 1 signer, same certificate as 1.0.15 (`e443c869…5197`); versionCode 27; permissions INTERNET (update check), USE_BIOMETRIC, USE_FINGERPRINT, WAKE_LOCK, RECEIVE_BOOT_COMPLETED, app-private dynamic receiver; no network-state permissions |
| In-place upgrade 1.0.15 → 1.0.27 on the Pixel | Installed over the official 1.0.15 (same signing key). Notification Access kept; settings kept (Google Messages exclusion still active); update consent dialog shown; old-archive notice shown; capture works. Cold start 79 ms (warm process). Pass |
| Automatic update check | Allowed in the consent dialog; 1.0.27 is the latest release, so no popup. Pass |
| Alarms / wake locks held by the app | 0 / 0 (`dumpsys alarm`, `dumpsys power`) |

### Battery results (release builds)

CPU time of the app process in 100 Hz ticks for the workloads described above (lower is better):

| Workload | 1.0.15 (installed for hours, not force-compiled) | 1.0.27 (just installed, JIT) | 1.0.27 (AOT `-m speed`) |
|---|---|---|---|
| W1: 120 non-message notifications | 66, 66 | 37, 48 | 36, 40, 39 |
| W2: 60 new chat messages | 195, 87 | 231, 172 | 177, 134 |
| W3: 30 bursts of 8 reposts | 175, 148 | 108, 111 | 92, 84, 71 |

- W1 (notifications from non-messaging apps, the bulk of what a listener sees all day) and W3 (re-posted
  conversation updates) cost clearly less: roughly **−35 %** and **−30 to −50 %**.
- W2 (each notification carries a genuinely new message that is stored) is not cheaper and is noisy (1.0.15 itself
  varied 87–195). Each stored message is now encrypted (AES-256-GCM per field plus HMAC indexes) and there is no
  full-text index; a `simpleperf` profile showed the time split between the Room transaction, the capture engine,
  adapter parsing and regex matching.
- A follow-up experiment that merged the noise regexes into one alternation and added digit-count guards before the
  sensitive-content regexes was measured A/B against 1.0.27 (both AOT-compiled, measured during the same workloads):
  no measurable gain (W2 197/152 vs 177/134), so it was **not** shipped.
- While capture is paused the listener is unbound, so paused capture costs no CPU per notification.

## Round 3 (2026-11-01): issue #3 — reply, saved-message notifications, start tab

Debug build (`com.riyadm.unsendvault.debug`, version 1.0.28) on the same Pixel 6 / Android 17. The chat app was a
throwaway helper app built only for this test (not part of the repository) that posts MessagingStyle notifications with
a RemoteInput reply action, logs replies it receives, and re-posts the chat with the reply, like real chat apps do. All
chat content was synthetic.

### Automated

- Unit tests: 446 run, **445 passed**, 1 skipped (as before), 0 failed (23 new: notification policy and per-chat state, ingest details,
  reply gateway on Robolectric including the fired RemoteInput intent and chat routing, conversation reply state,
  start tab, settings persistence and lock rules, conversation deep link, settings cancel hooks).
- Instrumented tests on the Pixel: **29 passed**, 0 failed.
- Lint: no new warnings (the 15 existing widget `UnusedAttribute` warnings remain). Release APK size unchanged
  (3.1 MB). `scripts/verify-release.sh` run on a test-signed copy of the release APK: all permission checks pass
  (`POST_NOTIFICATIONS` allowed), the only failure was the expected debug signing certificate.

### Manual on the device

| Test | Result |
|---|---|
| Chat whose notification is showing with a reply action | Reply box "Reply via …" shown |
| Send a reply | Helper app received exactly the typed text through its own reply action; its updated notification was archived as "You: …" |
| Two chats showing, reply in the second | Reply went to the second chat only |
| Chat's notification dismissed, chat opened | "Replies work only while …'s notification for this chat is still showing." |
| New message arrives while that chat is open | Reply box reappeared without leaving the screen |
| Keyboard open | Reply bar sits directly above the keyboard (screenshot checked) |
| Turn on "Notify when messages are saved" | Android 17 permission prompt shown; after Allow the switch is on |
| New message, content hidden (default) | Silent (importance low), grouped notification "Saved from Reply Probe / 1 new message saved", lock-screen version generic |
| Two more messages | Count became 2, then 3 in the same notification |
| "Show names and text" on | Title = chat name, app as subtext, last lines listed |
| Tap the notification in the shade | UnsendVault opened that chat; the notification was removed |
| New message while that chat is open | No notification posted; after leaving the chat the next message posted one again |
| Turn the option off with two notifications showing | Both removed; later messages posted none |
| "Open on" = Chats, then force-stop and relaunch | Archive opened on the Chats tab |

Not tested on the device: WhatsApp, WhatsApp Business, Viber and other real chat apps (not installed; their reply
actions are standard RemoteInput actions but individual apps may behave differently); enabling the app lock (needs the
owner's credential), which is covered by the settings view-model unit test that checks posted notifications are
cancelled.

During setup the debug build's catch-up again read real SMS from Google Messages; they were deleted immediately and the
app was excluded in the debug build.

### Release v1.0.29 (published by CI)

The push of these changes built, signed and published
[v1.0.29](https://github.com/riyadmondol2006/UnsendVault/releases/tag/v1.0.29). The downloaded APK (3,137,218 bytes)
matches its published SHA-256, is signed with the same certificate as earlier releases
(SHA-256 `e443c869…75135197`), reports `com.riyadm.unsendvault` 29 / 1.0.29, and declares exactly `INTERNET`,
`POST_NOTIFICATIONS`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` and the internal
dynamic-receiver permission. (The debug build tested above reported 1.0.28 because it was built before the commit.)
