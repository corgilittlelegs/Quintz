# GitHub debug signing and updates

The manual **Build Debug APK** workflow signs `com.quintz.wifi.debug` using one
permanent debug key from the repository secret `DEBUG_KEYSTORE_BASE64`. Missing
or invalid signing material fails the build; there is no temporary-key fallback.
The workflow verifies the APK certificate against the restored key before upload.
It supplies the keystore path directly to Gradle instead of relying on the runner's
default debug-keystore location.

## Signing key

The dedicated CI key is backed up locally in the ignored
`keystore/ci-debug.keystore` file. It uses alias `androiddebugkey` and the standard
debug store/key password `android`. Keep the private key out of Git, artifacts,
and logs, and retain a secure backup. This key is separate from the local Android
Studio debug key and from release signing keys.

To restore the GitHub secret from that backup without printing its contents:

```bash
base64 < keystore/ci-debug.keystore |
  gh secret set DEBUG_KEYSTORE_BASE64 --repo corgilittlelegs/Quintz
```

Do not replace the secret with a freshly generated key once testers have installed
builds signed with it. Replacing it breaks normal updates for those installations.

## Versions and installation

The **patch**, **minor**, or **major** choice previews the corresponding increase
from the latest stable `vMAJOR.MINOR.PATCH` tag, using the same calculation as the
stable release workflow. For example, stable `2.1.0` plus **patch** yields app
version `2.1.1-debug`, release tag `debug-v2.1.1`, and APK
`Quintz-2.1.1-debug.apk`. No GitHub run number appears in these names. The selected
source must include the latest stable release.

CI uses Android `versionCode = 1000000 + GITHUB_RUN_NUMBER`, keeping codes above
the previous commit-count values. Each new run increases the code even when an
older source ref is selected. Rerunning an existing run keeps its version code.
Keep the workflow's identity and run-number history; recreating it can reset the
counter and requires reviewing the version-code baseline.

Full Git history is fetched to calculate the upcoming version from stable tags.
The artifact also contains the matching `Quintz-<version>-debug.apk.sha256` and
`debug-build-info.txt` with the app version, commit, workflow identity, internal
version code, and public certificate SHA-256 fingerprint.

Updates require the same signing certificate and a version code at least as high
as the installed APK. APKs from earlier runs with temporary keys, and APKs signed
by a different local debug key, cannot update to the permanent CI key normally.
Those installations need a one-time uninstall/reinstall, which clears app data.
Before uninstalling Quintz, restore Auto Roam/unlock any pinned Wi-Fi profile so
the app can restore the profile it changed. Future CI APKs can then update in place.

A default local debug build has a separate key and a much lower version code.
To produce a local APK compatible with a CI installation, explicitly use the CI
key and supply an appropriate `-PversionCode`; do not overwrite your default
Android Studio keystore. Release signing and versioning are independent because
the release app uses package ID `com.quintz.wifi`.

## Debug releases

Every successful manual build creates or updates its version's prerelease in
GitHub's **Releases** section, titled `Quintz <version>-debug`, tagged
`debug-v<version>`. Download `Quintz-<version>-debug.apk` directly from its assets,
along with the matching checksum and build details. These releases are marked
as prereleases and are never promoted to the latest stable release. The Actions
artifact is named `Quintz-<version>-debug`; a whole-workflow retry replaces that
run's existing artifact.

Publication runs only after tests, lint, APK signature and package/version checks,
and artifact upload succeed. A separate publication job verifies the downloaded
checksum and has the repository write permission needed to create or update the
release. New assets upload to a draft; an existing debug prerelease becomes a
draft while its assets are replaced. A failed update leaves it unpublished until
a successful retry. The debug tag moves to the exact commit of the replacement
APK. Builds serialize, and publication rejects an older internal code, a stable
release that changed during the build, or an immutable/non-prerelease entry.

Keep repository release immutability disabled for this mutable debug channel.
Stable tags and releases are never rewritten; the `debug-v` prefix is excluded
from stable versioning's `v*` selection. Older run-based debug releases remain
historical entries and are not migrated or removed automatically.
