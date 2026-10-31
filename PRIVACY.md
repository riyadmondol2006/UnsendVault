# Privacy policy

Effective 2026-11-01. Applies to UnsendVault for Android, published by riyadmondol2006 at
https://github.com/riyadmondol2006/UnsendVault.

## Summary

UnsendVault stores message previews from your notifications **on your phone only**, with the message content encrypted. The
developer receives nothing: there are no analytics, advertising, crash reporting or other third-party services. The
only network use is an **optional update check** (off until you allow it, see below); it never sends anything from your
archive.

## What the app reads

Only after you grant **Notification Access** in Android's settings, UnsendVault receives the notifications other apps
post. From notifications it treats as messages (by default), it reads the app name, conversation title, sender name,
message text or placeholder (for example "Photo"), and timestamps. It also notes when a notification was removed and
the reason Android gives, for display only.

It does not read your contacts, SMS database, files, photos, location, microphone or camera, and it cannot read
anything inside other apps that was not shown in a notification.

## What the app stores, and where

- Archived messages, conversation names, update history and deletion markers: in an app-private database. Message
  text, sender names, conversation titles, edit history and deletion markers are each encrypted (AES-256-GCM) with a
  key that is generated on the device and protected by the Android Keystore. App names, package names, timestamps,
  favorite/deleted flags and message counts are stored without this extra encryption; like everything in app-private
  storage they are still covered by Android's own file-based encryption of the device.
- Your settings: in app-private storage (DataStore).
- Before storage, one-time codes and sensitive numbers are replaced by a placeholder when the corresponding filters
  are on (they are on by default). Content that Android itself hides is not stored.

Nothing is uploaded. Android cloud backup and device-to-device transfer are disabled for the app.

## Exports

If you use **Export**, the app writes a password-encrypted file (PBKDF2-HMAC-SHA256 key derivation, AES-256-GCM) to a
location you pick in the system file picker. Where that file goes afterwards (for example a cloud drive) is your
choice and outside the app. Without the password the file cannot be read, and the password cannot be recovered.

## Retention and deletion

- Auto-delete removes messages older than the period you choose (default 30 days; favorites are kept; 0 keeps
  everything).
- You can delete single messages, conversations, everything from one app, or the whole archive at any time.
- Uninstalling the app deletes all its data and its Keystore key. Existing export files are not affected.

## Widgets and screen privacy

Widgets show counts and app names unless you turn on message content, and never message content while the app lock is
on. The "secure screen" option (on by default) hides the app in Recents and blocks screenshots.

## Notifications from UnsendVault (optional)

If you turn on **Notify when messages are saved** (off by default), UnsendVault posts its own silent notification per
chat when it saves new messages. By default it only names the source app and the number of messages; chat names and
message text appear only if you also turn on **Show names and text in notifications**, which is unavailable while the
app lock is on. These notifications are hidden on the lock screen, are not sent anywhere, and are removed when you turn
the option off, hide content, enable the app lock or delete all data.

## Replying from UnsendVault

When you send a reply from a chat in UnsendVault, the text is handed to the chat app's own notification reply action on
your phone, the same way a smartwatch or car reply works. UnsendVault does not connect to the chat service; the chat
app sends the message. It only reads that one app's active notifications at that moment to find the right chat, and
stores nothing extra (the chat app's updated notification is archived like any other).

## Other people's messages

The archive contains messages other people sent you. They may have deleted a message on purpose. Please use the app
responsibly and in line with the law where you live. UnsendVault cannot be enabled remotely and is not designed or
allowed to be used for monitoring someone else's device.

## Children

UnsendVault is not directed at children and collects no data from anyone.

## Changes

Changes to this policy are made in this file in the public repository, with the date above updated.

## Contact

Open an issue at https://github.com/riyadmondol2006/UnsendVault/issues, or for security matters see
[SECURITY.md](SECURITY.md).

## Update check (optional)

After setup, UnsendVault asks once whether it may check for updates. Only if you choose **Allow**, it sends one HTTPS
request to `https://api.github.com/repos/riyadmondol2006/UnsendVault/releases/latest` at most once a day while you use
the app (never in the background). The request contains only standard headers and `User-Agent: UnsendVault/<version>`;
GitHub sees your IP address and the app version, like any website visit, under
[GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
Nothing from your archive, your notifications or your installed apps is sent. "Check now" in Settings sends the same
request once when you tap it. You can turn the automatic check off at any time in Settings > Updates. Details:
[docs/UPDATES.md](docs/UPDATES.md).
