# Release signing after the exposed key

The original release key was committed to Git. Removing it from the current tree does not remove it from Git history or from copies already downloaded. Never use the old key alone for a new release.

The release build now produces `app-release-unsigned.apk`. `scripts/sign-release.sh` signs it using both the original key and a new key plus their APK Signature Scheme v3 lineage. It requests the rotated signature starting at API 28 and verifies the result at API 29, 32, 33, and 35. Signature verification does not establish that an existing installation accepts the APK as an update; test that on real devices before publishing.

## Local signing material

The initial rotated key and lineage are in ignored, owner-readable files:

| File | Purpose |
| --- | --- |
| `keystore/release.jks` | Legacy key, retained locally for lineage signing |
| `keystore/rotated-release.jks` | New release key, alias `quintz-2026` |
| `keystore/release-lineage.bin` | Legacy-to-new signing lineage |
| `keystore/rotated-release-password.txt` | New key password; store a separate secure backup |

These files are not part of the repository. Back up the new key, lineage, and password separately in a secure location before relying on them for releases. Do not put any key or password in Git, a release artifact, or a support log.

## GitHub Actions secrets

Set these repository secrets before triggering a release:

| Secret | Value |
| --- | --- |
| `LEGACY_KEYSTORE_BASE64` | Base64 of `keystore/release.jks` |
| `LEGACY_KEY_ALIAS` | Alias of the original key |
| `LEGACY_KEYSTORE_PASSWORD` | Original keystore password |
| `LEGACY_KEY_PASSWORD` | Original key password |
| `RELEASE_KEYSTORE_BASE64` | Base64 of `keystore/rotated-release.jks` |
| `RELEASE_KEY_ALIAS` | `quintz-2026` |
| `RELEASE_KEYSTORE_PASSWORD` | Password from the local password file |
| `RELEASE_KEY_PASSWORD` | Same password for this PKCS12 key |
| `SIGNING_LINEAGE_BASE64` | Base64 of `keystore/release-lineage.bin` |

The workflow fails before publishing if signing material is missing or APK verification fails. Test an update from the last published APK on Android 10–12 and Android 13+ before the first rotated release. Do not assume that APK signature verification alone proves update compatibility. The old private key remains exposed in history, so advise existing users to obtain updates only from the official GitHub release page. A fresh package ID and reinstall is the stronger route if the old key must no longer be trusted at all; that route loses normal in-place updates and existing app data unless migration is planned.
