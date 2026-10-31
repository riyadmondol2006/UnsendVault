#!/usr/bin/env bash
# Checks the signed release APK before anything is published. Every one of these must hold or the script exits
# non-zero:
#
#   * apksigner verifies the APK; it is signed with APK Signature Scheme v2 or v3 by exactly one certificate, and that
#     certificate is not the Android debug certificate
#   * when the keystore is given, the APK certificate is the one in that keystore (the build really used the CI key)
#   * the APK carries the expected application id, versionName and versionCode, and is not debuggable
#   * the APK declares no network-state permission and no permission outside UnsendVault's allowlist (the same
#     allowlist the Gradle task verifyPermissions<Variant> enforces on the merged manifest); INTERNET is allowed only
#     for the opt-in update check
#
# Usage: scripts/verify-release.sh --apk FILE --version-name 1.0.57 --version-code 57
# Environment:
#   SIGNING_KEYSTORE_PATH, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS   optional; enable the keystore comparison
#   ANDROID_HOME / ANDROID_SDK_ROOT   SDK with build-tools (apksigner, aapt2); falls back to sdk.dir in local.properties
# Prints the signing certificate as `cert_sha256=AA:BB:...` (and appends it to $GITHUB_OUTPUT when that is set).
set -euo pipefail

expected_package=com.riyadm.unsendvault
allowed_permissions=(
  android.permission.INTERNET # update check only (opt-in)
  android.permission.POST_NOTIFICATIONS # opt-in saved-message notifications
  android.permission.USE_BIOMETRIC
  android.permission.USE_FINGERPRINT
  android.permission.WAKE_LOCK
  android.permission.RECEIVE_BOOT_COMPLETED
  "$expected_package.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
)
network_permissions=(
  android.permission.ACCESS_NETWORK_STATE
  android.permission.ACCESS_WIFI_STATE
  android.permission.CHANGE_NETWORK_STATE
)

apk="" version_name="" version_code=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk) apk=${2:?}; shift 2 ;;
    --version-name) version_name=${2:?}; shift 2 ;;
    --version-code) version_code=${2:?}; shift 2 ;;
    *) echo "usage: $0 --apk FILE --version-name X.Y.Z --version-code N" >&2; exit 2 ;;
  esac
done

problems=()
fatal() { echo "verify-release: $*" >&2; exit 1; }
problem() { problems+=("$*"); echo "  FAIL: $*" >&2; }
pass() { echo "  ok: $*"; }
heading() { echo; echo "== $*"; }

[[ -n $apk && -n $version_name && -n $version_code ]] || fatal "--apk, --version-name and --version-code are all required"
[[ -f $apk ]] || fatal "$apk does not exist"
[[ $apk != *unsigned* ]] || fatal "$apk is an unsigned build output; the release signing key was not applied"

# Tool output is parsed, so keep it in English whatever the machine's locale is.
jopts=(-J-Duser.language=en -J-Duser.country=US)

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk && -f local.properties ]]; then sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n 1); fi
[[ -n $sdk && -d $sdk/build-tools ]] || fatal "no Android SDK with build-tools found (set ANDROID_HOME)"
# The newest installed build-tools.
build_tools=$sdk/build-tools/$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort -V | tail -n 1)
apksigner=$build_tools/apksigner
aapt2=$build_tools/aapt2
[[ -x $apksigner && -x $aapt2 ]] || fatal "apksigner or aapt2 is missing from $build_tools"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

normalize_lines() { tr -d ': ' | tr '[:upper:]' '[:lower:]'; } # fingerprints -> bare lowercase hex, one per line
colonize() { sed 's/../&:/g; s/:$//' | tr '[:lower:]' '[:upper:]'; }

# --- APK signature -----------------------------------------------------------------------------------------------
heading "APK signature ($(basename "$apk"))"
"$apksigner" verify --verbose --print-certs "$apk" > "$work/apksigner.txt" 2>&1 || { cat "$work/apksigner.txt" >&2; fatal "apksigner rejected the APK"; }
schemes=$(sed -n 's/^Verified using \(v[0-9.]*\) scheme.*: true$/\1/p' "$work/apksigner.txt" | paste -sd, -)
if grep -Eq '^Verified using v[23](\.[0-9]+)? scheme .*: true' "$work/apksigner.txt"; then
  pass "signature schemes verified: $schemes"
else
  problem "the APK is not signed with APK Signature Scheme v2 or v3 (verified: ${schemes:-none})"
fi
# apksigner words the signer lines differently across build-tools versions ("Signer #1 certificate ..." or
# "V2 Signer: certificate ..."), and repeats them per scheme, so collect every certificate it names.
apk_certs=$(sed -n 's/^.*[Ss]igner[^:]*[: ] *certificate SHA-256 digest: //p' "$work/apksigner.txt" | normalize_lines | sort -u)
apk_dns=$(sed -n 's/^.*[Ss]igner[^:]*[: ] *certificate DN: //p' "$work/apksigner.txt" | sort -u)
[[ $apk_certs =~ ^[0-9a-f]{64}$ ]] || { cat "$work/apksigner.txt" >&2; fatal "the APK must have exactly one signing certificate (apksigner reported: ${apk_certs:-none})"; }
apk_cert=$apk_certs
if [[ $apk_dns == *"Android Debug"* ]]; then problem "the APK is signed with the Android debug certificate"; else pass "signed by a non-debug key ($apk_dns)"; fi
pass "certificate SHA-256 $(colonize <<< "$apk_cert")"

# --- keystore ----------------------------------------------------------------------------------------------------
if [[ -n ${SIGNING_KEYSTORE_PATH:-} && -n ${SIGNING_STORE_PASSWORD:-} && -n ${SIGNING_KEY_ALIAS:-} ]]; then
  heading "Signing key"
  key_cert=$(keytool "${jopts[@]}" -exportcert -alias "$SIGNING_KEY_ALIAS" -keystore "$SIGNING_KEYSTORE_PATH" -storepass:env SIGNING_STORE_PASSWORD | sha256sum | cut -d' ' -f1)
  if [[ $key_cert == "$apk_cert" ]]; then pass "the APK is signed with the key in the keystore"; else problem "the APK was not signed with the key in the keystore"; fi
fi

# --- APK metadata ------------------------------------------------------------------------------------------------
heading "APK metadata"
"$aapt2" dump badging "$apk" > "$work/badging.txt" 2> /dev/null
badging_package=$(sed -n "s/^package: name='\([^']*\)' versionCode='\([^']*\)' versionName='\([^']*\)'.*/\1 \2 \3/p" "$work/badging.txt" | head -n 1)
if [[ $badging_package == "$expected_package $version_code $version_name" ]]; then
  pass "package, versionCode and versionName are $badging_package"
else
  problem "the APK reports '$badging_package' but '$expected_package $version_code $version_name' was expected"
fi
min_sdk=$(sed -n "s/^\(minSdkVersion\|sdkVersion\):'\([0-9]*\)'.*/\2/p" "$work/badging.txt" | head -n 1)
target_sdk=$(sed -n "s/^targetSdkVersion:'\([0-9]*\)'.*/\1/p" "$work/badging.txt" | head -n 1)
pass "minSdk ${min_sdk:-?}, targetSdk ${target_sdk:-?}"
if grep -q '^application-debuggable' "$work/badging.txt"; then problem "the APK is debuggable"; else pass "the APK is not debuggable"; fi

# --- permissions -------------------------------------------------------------------------------------------------
heading "Permissions"
# Declared permissions only ("uses-permission" and "uses-permission-sdk-23"); "uses-implied-permission" lines are
# aapt2's guesses for very old target SDKs and are not in the manifest.
mapfile -t declared < <(sed -n "s/^uses-permission\(-sdk-23\)\{0,1\}: name='\([^']*\)'.*/\2/p" "$work/badging.txt" | sort -u)
network_declared=0
for permission in "${network_permissions[@]}"; do
  if printf '%s\n' "${declared[@]}" | grep -qxF "$permission"; then
    problem "the APK declares $permission; UnsendVault never needs network-state access"
    network_declared=1
  fi
done
for permission in "${declared[@]}"; do
  [[ -n $permission ]] || continue
  if printf '%s\n' "${allowed_permissions[@]}" | grep -qxF "$permission"; then
    pass "$permission (allowed)"
  else
    problem "the APK declares $permission, which is outside the allowlist"
  fi
done
if [[ $network_declared -eq 0 ]]; then pass "no network-state permission"; fi

# --- result ------------------------------------------------------------------------------------------------------
echo
if [[ ${#problems[@]} -gt 0 ]]; then
  echo "verify-release: ${#problems[@]} check(s) failed" >&2
  exit 1
fi
cert_sha256=$(colonize <<< "$apk_cert")
echo "cert_sha256=$cert_sha256"
if [[ -n ${GITHUB_OUTPUT:-} ]]; then echo "cert_sha256=$cert_sha256" >> "$GITHUB_OUTPUT"; fi
