#!/usr/bin/env bash
# Sign the unsigned spike release APK with the repo release keystore.
#
# The keystore file is committed (docs/01 ADR-003); the passwords live in the main checkout's
# local.properties, which is git-ignored and never read by the spike build itself. Override the
# path with SPIKE_KEYSTORE_PROPS if the repo lives somewhere else.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SPIKE_ROOT="$(cd "$HERE/.." && pwd)"
REPO_ROOT="${SPIKE_REPO_ROOT:-/Users/jeffrey/temp/iptv-player-workspace/iptv-player}"
PROPS="${SPIKE_KEYSTORE_PROPS:-$REPO_ROOT/local.properties}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-$HOME/Library/Android/sdk/build-tools/36.1.0}"

UNSIGNED="$SPIKE_ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
ALIGNED="$SPIKE_ROOT/app/build/outputs/apk/release/app-release-aligned.apk"
SIGNED="$SPIKE_ROOT/app/build/outputs/apk/release/app-release-spike.apk"

[[ -f "$UNSIGNED" ]] || { echo "missing $UNSIGNED (run assembleRelease first)" >&2; exit 1; }
[[ -f "$PROPS" ]] || { echo "missing keystore properties: $PROPS" >&2; exit 1; }

store_file="$(sed -n 's/^RELEASE_STORE_FILE=//p' "$PROPS")"
store_pass="$(sed -n 's/^RELEASE_STORE_PASSWORD=//p' "$PROPS")"
key_alias="$(sed -n 's/^RELEASE_KEY_ALIAS=//p' "$PROPS")"
key_pass="$(sed -n 's/^RELEASE_KEY_PASSWORD=//p' "$PROPS")"

"$BUILD_TOOLS/zipalign" -p -f 4 "$UNSIGNED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$REPO_ROOT/$store_file" --ks-key-alias "$key_alias" \
  --ks-pass "pass:$store_pass" --key-pass "pass:$key_pass" \
  --out "$SIGNED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" verify --print-certs "$SIGNED" | head -4
shasum -a 256 "$SIGNED"
