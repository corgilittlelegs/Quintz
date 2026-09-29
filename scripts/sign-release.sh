#!/usr/bin/env bash
set -euo pipefail

# Sign only with the old-to-new lineage. All key material is supplied externally.
: "${APKSIGNER:?Set APKSIGNER to Android SDK apksigner}"
: "${LEGACY_KEYSTORE:?Set LEGACY_KEYSTORE}"
: "${LEGACY_KEY_ALIAS:?Set LEGACY_KEY_ALIAS}"
: "${LEGACY_KEYSTORE_PASSWORD:?Set LEGACY_KEYSTORE_PASSWORD}"
: "${LEGACY_KEY_PASSWORD:?Set LEGACY_KEY_PASSWORD}"
: "${RELEASE_KEYSTORE:?Set RELEASE_KEYSTORE}"
: "${RELEASE_KEY_ALIAS:?Set RELEASE_KEY_ALIAS}"
: "${RELEASE_KEYSTORE_PASSWORD:?Set RELEASE_KEYSTORE_PASSWORD}"
: "${RELEASE_KEY_PASSWORD:?Set RELEASE_KEY_PASSWORD}"
: "${SIGNING_LINEAGE:?Set SIGNING_LINEAGE}"

unsigned_apk="${1:?Pass unsigned APK path}"
signed_apk="${2:?Pass signed APK path}"
test -s "$unsigned_apk"
test -s "$LEGACY_KEYSTORE"
test -s "$RELEASE_KEYSTORE"
test -s "$SIGNING_LINEAGE"

"$APKSIGNER" sign \
    --ks "$LEGACY_KEYSTORE" --ks-key-alias "$LEGACY_KEY_ALIAS" \
    --ks-pass env:LEGACY_KEYSTORE_PASSWORD --key-pass env:LEGACY_KEY_PASSWORD \
    --next-signer \
    --ks "$RELEASE_KEYSTORE" --ks-key-alias "$RELEASE_KEY_ALIAS" \
    --ks-pass env:RELEASE_KEYSTORE_PASSWORD --key-pass env:RELEASE_KEY_PASSWORD \
    --lineage "$SIGNING_LINEAGE" --rotation-min-sdk-version 28 \
    --out "$signed_apk" "$unsigned_apk"

for sdk in 29 32 33 35; do
    "$APKSIGNER" verify --min-sdk-version "$sdk" --max-sdk-version "$sdk" "$signed_apk"
done
