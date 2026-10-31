# Releasing

Every push to `main` becomes a release. There is no release branch, no tag to push by hand and no button to press:
the workflow `.github/workflows/ci.yml` ("CI and Release") builds the commit, checks it, signs it and publishes it as a
[GitHub Release](https://github.com/riyadmondol2006/UnsendVault/releases/latest) holding the signed APK.

UnsendVault is distributed only as a signed APK on GitHub Releases. It is not on Google Play.

## What happens on a push to `main`

1. **`verify`** (also on pull requests and manual runs):
   - validates the Gradle wrapper (`gradle/actions/wrapper-validation`);
   - sets up Temurin JDK 21 and Gradle (`gradle/actions/setup-gradle`), installs SDK platform 37
     (`platforms;android-37.0`) and `build-tools;37.0.0` if the runner image does not already have them;
   - runs `:app:testDebugUnitTest :app:lintDebug :app:verifyPermissionsDebug :app:verifyPermissionsRelease
     :app:assembleDebug :app:assembleDebugAndroidTest`;
   - uploads the test/lint reports and the debug APK as workflow artifacts.
2. **`release`** runs only after `verify` passes, only for pushes to `main` of the original repository (never forks,
   never pull requests), and not when the newest commit message of the push contains `[skip release]`. It is the only
   job with `contents: write`. It:
   1. works out the version with `scripts/compute-version.sh --strict` (full history is required);
   2. restores the keystore from `SIGNING_KEYSTORE_BASE64` into `$RUNNER_TEMP` with `umask 077`, and **stops if any
      of the four secrets is missing**. It signs a throwaway jar with `jarsigner` to prove the store password, alias and
      key password work before the long build starts;
   3. builds `:app:verifyPermissionsRelease :app:assembleRelease` with `UNSENDVAULT_REQUIRE_SIGNING=true` (Gradle fails
      instead of producing an unsigned APK) and the computed `UNSENDVAULT_VERSION_NAME` / `UNSENDVAULT_VERSION_CODE`;
   4. runs `scripts/verify-release.sh` on `app/build/outputs/apk/release/app-release.apk` (see below);
   5. stages the public files and release notes with `scripts/stage-release.sh` and keeps a copy as a workflow artifact
      for 14 days;
   6. publishes with `scripts/publish-release.sh`: creates tag `v<version>` on the commit, creates a **draft** release
      with `gh release create`, checks that every asset uploaded, then publishes it (not a prerelease; marked Latest
      unless a newer release already exists);
   7. deletes the keystore from the runner (`if: always()`, so also after a failure).
3. **`instrumented`** (manual runs and a weekly schedule only) runs `connectedDebugAndroidTest` on an API 35 emulator.

Secrets are passed as environment variables **only to the steps that use them** (restore, build, verify). The
checkout uses `persist-credentials: false`; publishing uses the job's `GITHUB_TOKEN` through `GH_TOKEN`. All actions
are pinned to a major version (`actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/*@v4`,
`actions/upload-artifact@v4`, `reactivecircus/android-emulator-runner@v2`); Dependabot proposes updates weekly.

Runs for `main` go one at a time and a started release is never cancelled. If you push again while one is running,
only the newest waiting run goes next; its release covers the commits of any run dropped in between.

### Release assets

| File | What it is |
|---|---|
| `UnsendVault-<version>.apk` | The signed app. |
| `UnsendVault-<version>.apk.sha256` | The APK's SHA-256 in `sha256sum` format: `<64 hex>  UnsendVault-<version>.apk`. |
| `mapping-<version>.zip` | R8 mapping for de-obfuscating crash stack traces. Not needed to use the app. |
| `SHA256SUMS.txt` | SHA-256 of all the files above. |

The release notes contain install instructions, the commits since the previous `v*` tag, the APK SHA-256 and the
**signing certificate SHA-256**. GitHub's categorised notes (`.github/release.yml`) are appended.

### What `verify-release.sh` proves before anything is published

- `apksigner verify --verbose --print-certs` accepts the APK, it is signed with **APK Signature Scheme v2 or v3**, by
  exactly **one** certificate, and that certificate's DN is not the Android debug certificate (`CN=Android Debug`);
- the certificate is the one in the keystore restored from the secrets (`keytool -exportcert`), so the build really
  used the release key;
- `aapt2 dump badging` reports application id `com.riyadm.unsendvault` with the computed `versionCode` and
  `versionName`, and the APK is not debuggable;
- the APK declares no network-state permission (`ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_NETWORK_STATE`)
  and no permission outside the allowlist `INTERNET` (opt-in update check only), `POST_NOTIFICATIONS` (opt-in saved-message notifications), `USE_BIOMETRIC`, `USE_FINGERPRINT`,
  `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`,
  `com.riyadm.unsendvault.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. This mirrors the Gradle task
  `verifyPermissions<Variant>`, which already checks the merged manifest at build time; the script checks the final APK.

`aapt2` and `apksigner` come from the newest `build-tools` in the SDK.

## The four repository secrets

Set them under **Settings > Secrets and variables > Actions > Repository secrets**:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | The keystore file (`.jks` or PKCS#12), base64-encoded on a single line. |
| `SIGNING_STORE_PASSWORD` | The keystore password. |
| `SIGNING_KEY_ALIAS` | The alias of the signing key inside the keystore. |
| `SIGNING_KEY_PASSWORD` | The key's password (for PKCS#12 keystores this is normally the same as the store password). |

`app/build.gradle.kts` reads `SIGNING_KEYSTORE_PATH` (set by the workflow to the restored file),
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. Locally you can use an untracked
`keystore.properties` in the project root instead (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); `*.jks`,
`*.keystore` and `keystore.properties` are git-ignored.

### Creating the keystore and its base64 value

Create the key once and keep it safe. **Losing it means no future release can update existing installs**; Android
refuses an update signed with a different certificate (APK Signature Scheme v3 key rotation needs the old key too).

```bash
keytool -genkeypair \
  -keystore unsendvault-release.jks -storetype PKCS12 \
  -alias unsendvault -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=riyadmondol2006, O=UnsendVault"

# One line, no wrapping (Linux). On macOS: base64 -i unsendvault-release.jks | tr -d '\n'
base64 -w0 unsendvault-release.jks > unsendvault-release.jks.b64
```

Create the keystore with the same JDK major version CI uses (Temurin 21). In local testing on 2026-10-29, a PKCS#12
keystore written by the `keytool` of JDK 25 could not be read by AGP 9.5.0 during `packageRelease`
(`KeytoolException: ... Tag number over 30 is not supported`), even though `jarsigner` accepted it. A PKCS#12
keystore written by JDK 21's `keytool`, and a JDK 25 one stored with the legacy PKCS#12 algorithms, both worked. If you hit that error, re-export the keystore:

```bash
keytool -J-Dkeystore.pkcs12.legacy -importkeystore \
  -srckeystore unsendvault-release.jks -destkeystore unsendvault-release-legacy.p12 -deststoretype PKCS12
```

The CI `jarsigner` probe cannot detect this case (it uses the JDK's own reader), so the failure would only show at
the packaging step.

Paste the content of `unsendvault-release.jks.b64` into `SIGNING_KEYSTORE_BASE64`, then delete the `.b64` file. Keep
an offline backup of the `.jks` and its passwords (for example in a password manager and on encrypted removable
storage). Never commit either.

The certificate fingerprint that users will compare against:

```bash
keytool -list -v -keystore unsendvault-release.jks -alias unsendvault | grep 'SHA256:'
```

## Versions

`versionName = MAJOR.MINOR.N` and `versionCode = N`, for example `1.0.57` and `57`.

- `MAJOR.MINOR` lives in `version.properties`. Raise `minor` (or `major`) in the commit that starts a feature release
  or a breaking change.
- `N` is the number of commits reachable from `HEAD` (`git rev-list --count HEAD`). It only grows on `main`, so every
  release installs over the previous one (Android refuses a lower `versionCode`).
- Debug builds get the same numbers with a `-debug` name suffix and the `.debug` application id suffix, so they install
  side by side with a release.

Priority, highest first: Gradle properties `-Punsendvault.versionCode=` / `-Punsendvault.versionName=`; environment
variables `UNSENDVAULT_VERSION_CODE` / `UNSENDVAULT_VERSION_NAME` (CI uses these, so build and release cannot
disagree); git; and with no git at all, code `1` and name `MAJOR.MINOR.0`.

`scripts/compute-version.sh` applies the same rules for the workflow and `./gradlew -q :app:printVersion` shows what
Gradle will use. With `--strict` the script refuses to guess: it fails without git or on a shallow clone.

Avoid force-pushing a rewritten `main`: the commit count could drop below an already published `versionCode`, and
`publish-release.sh` refuses to move an existing tag to a different commit.

## How users verify a download

Each release states the APK SHA-256 and the signing-certificate SHA-256 in its notes.

```bash
# 1. The file is the one that was published
sha256sum --check UnsendVault-1.0.57.apk.sha256        # prints: UnsendVault-1.0.57.apk: OK

# 2. It is signed by the UnsendVault release key (apksigner is in Android SDK build-tools)
apksigner verify --print-certs UnsendVault-1.0.57.apk | grep 'SHA-256'
```

The certificate digest must equal the one in the release notes (and in the README). It never changes between
releases. On a phone without a computer, Android itself enforces this for updates: an APK signed with another key
will not install over an existing UnsendVault. Tools such as AppVerifier can show the certificate hash of an installed
app or APK for comparison.

## Troubleshooting a failed release

| Symptom | Cause and fix |
|---|---|
| "Release signing secrets are missing" | Add the named secret(s). Nothing was built or published. |
| "Invalid keystore secret" | `SIGNING_KEYSTORE_BASE64` is not valid base64. Recreate it with `base64 -w0`. |
| "Signing key unusable" | Wrong store password, alias or key password. `keytool -list -keystore unsendvault-release.jks` shows the alias. |
| `UNSENDVAULT_REQUIRE_SIGNING is set but the release signing key is missing` | Gradle did not see `SIGNING_KEYSTORE_PATH` or a value was empty. |
| `packageRelease` fails with "Tag number over 30 is not supported" | The keystore format is too new for AGP; re-export it with legacy PKCS#12 algorithms (see "Creating the keystore"). |
| `verify-release.sh` fails | Read the `FAIL:` lines. A new permission usually means a library added one: remove it in `AndroidManifest.xml` with `tools:node="remove"` or reconsider the dependency. |
| "shallow clone" | The checkout lost `fetch-depth: 0`. |
| "tag ... already points at ..." | The version number is taken by another commit, which only happens if history was rewritten. Push a new commit. |
| Publishing failed after staging | The staged files are attached to the run as an artifact. Re-run the job; the script is idempotent. |

## Running the pieces locally

```bash
scripts/compute-version.sh               # version_name=1.0.57 / version_code=57
./gradlew -q :app:printVersion           # what Gradle will use

export SIGNING_KEYSTORE_PATH=$PWD/unsendvault-release.jks SIGNING_STORE_PASSWORD=... SIGNING_KEY_ALIAS=... SIGNING_KEY_PASSWORD=...
export UNSENDVAULT_REQUIRE_SIGNING=true
./gradlew :app:verifyPermissionsRelease :app:assembleRelease

scripts/verify-release.sh --apk app/build/outputs/apk/release/app-release.apk --version-name 1.0.57 --version-code 57
scripts/stage-release.sh --version-name 1.0.57 --cert-sha256 AA:BB:... \
  --apk app/build/outputs/apk/release/app-release.apk \
  --mapping app/build/outputs/mapping/release/mapping.txt \
  --out build/release-dist --notes-file build/release-notes.md
```

`publish-release.sh` needs `GH_TOKEN`, `GITHUB_REPOSITORY` and `GITHUB_SHA` and talks to the GitHub API; run it only
from CI.

## Files

| File | Role |
|---|---|
| `.github/workflows/ci.yml` | the pipeline |
| `.github/dependabot.yml` | weekly Gradle and GitHub Actions updates |
| `.github/release.yml` | categories for generated release notes |
| `version.properties` | `major` and `minor` |
| `app/build.gradle.kts` | version, signing, `printVersion`, `verifyPermissions<Variant>` |
| `scripts/compute-version.sh` | the version, for the workflow |
| `scripts/verify-release.sh` | checks the signed APK |
| `scripts/stage-release.sh` | names the assets, writes checksums and release notes |
| `scripts/publish-release.sh` | tag, draft, upload, publish |
