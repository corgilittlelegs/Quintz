# Quintz ⚡

> **Wi-Fi Band Preference, BSSID Pinning & RF Telemetry for Android.**

[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029%2B)-3DDC84?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Privilege](https://img.shields.io/badge/Privilege-Shizuku%20(No%20Root)-00C853?style=flat-square)](https://shizuku.rikka.app)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

---

## Overview

**Quintz** is a rootless Wi-Fi utility for choosing radios and monitoring connections on dual-band and mesh networks.

Routers can broadcast 2.4 GHz, 5 GHz, and 6 GHz under one SSID. Android may remain on a 2.4 GHz radio after a stronger higher-band radio becomes available. Quintz lets you prefer 5/6 GHz, pin a specific BSSID, or return a network to normal auto-roaming.

Quintz uses **[Shizuku](https://shizuku.rikka.app)** to access Android's Wi-Fi service **without requiring root**. It combines per-network steering intent, a background recovery watchdog, an AP scanner, and rolling RF telemetry. Available bands and successful transitions depend on the device, router, and Android Wi-Fi implementation.

---

## Key Features

### 🔒 1-Tap 5 GHz Preference (No Root Required)

- Selects a strong 5 GHz / 6 GHz BSSID on your network, then keeps the saved profile available for roaming.
- The preferred-band action is not a permanent BSSID pin: Android and the Wi-Fi firmware can still roam to another BSSID or band when conditions require it.
- Use a specific BSSID lock when you need to keep the profile pinned to one access point.
- Quintz stores Auto, Prefer 5 GHz, or Pin BSSID intent per SSID, so changing one network does not change the watchdog target for another.
- Supports WPA2-Personal, WPA3-SAE, Enhanced Open (OWE), and open networks.
- Profile changes require a compatible Android Wi-Fi framework and authorized Shizuku access.

### 🛡️ Smart Fallback Watchdog

- Runs as a foreground service and checks the connected band while the watchdog is enabled and Shizuku is available.
- The 5 GHz preference leaves the saved profile unpinned so Android or device firmware can roam to 2.4 GHz when needed. The watchdog does not trigger an unlock at a particular 5 GHz RSSI value.
- When the device is connected to the target SSID on 2.4 GHz, the watchdog scans for a same-SSID 5 GHz / 6 GHz BSSID. The default recovery threshold is `-72 dBm`.
- A successful `start-scan` command only means Android accepted the request. Quintz waits up to 12 seconds for evidence that the target network's 5/6 GHz scan result refreshed, and excludes those candidates if it did not.
- Recovery requires the same eligible BSSID in two fresh observations at least 10 seconds apart. If confirmed, Quintz requests a BSSID-specific transition, verifies that the target BSSID became active, then unpins the saved profile again so roaming remains allowed.
- For a pinned network, the watchdog monitors and restores that exact BSSID (on either band), then verifies the hard profile pin. Auto mode does not trigger band or BSSID recovery.
- The recovery scan interval starts at about 12 seconds and backs off to 30 seconds when no eligible candidate is found or a recovery attempt fails. Failed switch attempts also receive a retry cooldown that increases from 60 seconds up to 5 minutes.
- Automatic recovery checks fresh observations and trusted security information. Secured networks require a saved password; open and OWE networks require an explicit trusted radio choice instead of automatic same-name band selection.
- Missing credentials, unavailable Shizuku, stale results, profile recovery conflicts, or Android/OEM behavior can prevent or delay a switch. Recovery can interrupt the connection.

### ⚙️ How BSSID Pinning Works

- **Saved-profile changes**: A Shizuku user service reads and updates Android's Wi-Fi configuration, sets or clears its BSSID pin, and requests reassociation. Status and scan paths also use `cmd wifi` and `dumpsys wifi` where appropriate.
- **Verification and recovery**: Before changing a profile, Quintz saves an encrypted recovery record. It checks profile readback and the resulting connection before committing the new intent. Failed or interrupted transitions attempt to restore the previous profile and app settings; a changed or unverifiable profile can leave recovery pending.
- **Per-network MAC policy**: Shows Android's configured Device MAC or persistent Randomized MAC, separately from Quintz's saved preference and the observed address. Choosing the configured policy does nothing. In Auto mode, a change updates the saved Android profile for the next connection without issuing a reconnect. With an active BSSID pin or 5 GHz preference, Quintz reconnects the same network, keeps that mode and pin, and verifies the observed MAC. Router reservations can be affected.
- **Separate password storage**: The password status shows whether Quintz has its own encrypted copy, independently of Android's saved network. MAC-only changes reuse Android's existing profile and do not require a password copy in Quintz. Forgetting the Quintz copy leaves Android's saved network intact.
- **Device limits**: A saved-profile pin cannot guarantee uninterrupted association. The watchdog attempts to restore the selected BSSID for Pin BSSID intent, or return to an eligible 5/6 GHz radio for Prefer 5 GHz intent.

### 📊 Real-Time Roaming & RF Telemetry Monitor

- Rolling graphs for active connection RSSI and PHY link speed over the last minute.
- Connected signal and link-speed cursors stay at NOW, holding the latest value between readings; the cursor disappears if observations stop for more than 7.5 seconds.
- **Multi-AP Roaming Crossover Detection**: Plots recent same-network candidate scan readings as thin lines with dots at measured observations. Measured lines break across gaps longer than 20 seconds or channel/band changes. A lighter dashed tail holds the last known value to NOW; its dot stays at the actual observation time. Readings too old for a live comparison are marked stale, with dimmer held tails.
- Candidate scans consume Android's scan-completion notifications, with bounded polling when notifications are unavailable. Foreground scanning pauses briefly after success and backs off when results do not refresh. Candidate rows show observation age; they are not one-second live measurements.
- This scan path supports Android 10+ (the app's minimum version) through Shizuku. Scan latency and availability depend on Android, OEM firmware, and Wi-Fi hardware; neither scan requests nor completion notifications guarantee a fresh reading of every AP.
- **Color-Coded RF Quality Bands**: Visual thresholds for Optimal (`> -65 dBm`), Evaluation (`-65 to -75 dBm`), and Roam / Weak (`< -75 dBm`) zones.
- **Automated Handoff Event Tracking**: Drops timestamped event pins whenever band or BSSID transitions take place.
- **Candidate actions**: Bind to an eligible radio from the telemetry monitor. Cached history remains visible for up to a minute, but a live comparison requires a recent observation from the latest scan; displaying a radio does not make it actionable.

### 📊 AP Scanner & Channel Analyzer

- Scans and lists all nearby access points, frequencies, channel numbers, and MACs.
- Clear indicators for Wi-Fi standards (**11n**, **11ac**, **11ax**, **Wi-Fi 7**).
- Rows distinguish **ACTIVE** (the connected radio) from **PINNED** (the saved target).
- Actions show **`PIN`** for the active unpinned radio, **`UNPIN`** for a pinned radio, and **`BIND`** for other eligible radios. A saved pin can be cleared even when that radio is not the active connection.

### ⚡ Quick Settings Tile

- Switches an Auto network to Prefer 5 GHz, or returns a steered/pinned network to Auto from the Quick Settings shade.
- Requires authorized Shizuku and saved credentials for secured-network preference. Missing credentials or an open/OWE network's explicit radio selection opens the app.
- The subtitle shows connection/steering status. Returning the last steered network to Auto stops the watchdog; other saved targets can keep it enabled.

### 🔋 Background Work & Resource Use

- UI status polling, scanning, and telemetry stop when the app leaves the foreground. Scanner and telemetry work also follow the selected screen and telemetry pause state.
- An enabled watchdog continues status checks and recovery scans in its foreground service while the app is minimized. It uses scan backoff and switch cooldowns, and stops when no saved steering targets remain.
- Graph drawing caches reusable geometry and text measurements. Smooth animation preserves measurement timestamps; it does not create extra RF readings or guarantee a particular frame rate or battery cost.

---

## Prerequisites

- **Android 10 or later** (minimum API 29; compile/target SDK 35).
- **[Shizuku](https://shizuku.rikka.app)** (v13+ recommended).
- Profile capture and mutation currently allow SDK 29–36 and require successful runtime capability checks. This range does not establish compatibility with every device or manufacturer. WEP, enterprise Wi-Fi, Passpoint, ambiguous profiles, and unavailable profile credentials are not supported by the full profile-change path.

### Why Shizuku?
Since Android 10, Google restricted third-party applications from programmatically managing Wi-Fi networks and BSSID associations. Shizuku runs a privileged system service that lets user apps execute authorized system shell commands without modifying Android system files or rooting your device.

**Set up Shizuku:**

1. Install [Shizuku from Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) or its [official GitHub releases](https://github.com/RikkaApps/Shizuku/releases).
2. On **Android 11+**, follow Shizuku's wireless-debugging pairing and startup steps. On **unrooted Android 10**, start it through ADB from a computer. See the [official setup guide](https://shizuku.rikka.app/guide/setup/).
3. Open **Quintz** and grant Shizuku access when prompted.

With either non-root startup method, Shizuku must be started again after a device reboot. Android battery restrictions or loss of Shizuku access can interrupt watchdog recovery.

---

## Building from Source

### Requirements

- **JDK 21**
- **Android SDK Platform 35** and **Build Tools 35.0.0** (used by release signing)
- **Git**
- **Android SDK Platform Tools / ADB** for device installation and tests

Configure the SDK location using Android Studio or an ignored `local.properties` file with `sdk.dir`. The repository includes the Gradle wrapper.

### Steps

1. **Clone the repository:**
   ```bash
   git clone https://github.com/corgilittlelegs/Quintz.git
   cd Quintz
   ```

2. **Build an installable debug APK:**
   ```bash
   ./gradlew :app:assembleDebug
   ```

3. **Install to your connected device via ADB:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

Debug builds use package ID `com.quintz.wifi.debug` and can coexist with the release package `com.quintz.wifi`. Updating a debug installation requires the same debug signing key.

### Release Builds & Signing

```bash
./gradlew :app:assembleRelease
```

This produces `app/build/outputs/apk/release/app-release-unsigned.apk`, which must be signed before installation. Release builds enable R8 code and resource shrinking. The manual release workflow supplies version values and signs the APK using externally supplied legacy/rotated keys and their signing lineage. Follow [release signing](docs/release-signing.md) for the required environment variables and GitHub secrets, and [release versioning](docs/release-versioning.md) for version/tag behavior.

Local signing material belongs in the ignored `keystore/` directory and must be backed up separately. Gradle does not fall back to debug signing for release builds.

### Verification

Run the same build, unit-test, and lint gate as the verification workflow:

```bash
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease \
  --no-daemon --console=plain
```

Run Android instrumentation tests with a connected device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest --no-daemon --console=plain
```

Some profile-access/recovery tests require authorized Shizuku and specific device state, and skip when those prerequisites are absent. Passing the CI emulator tests does not verify real Wi-Fi reassociation, OEM compatibility, signed upgrades, or battery use.

## GitHub Actions

| Workflow | Trigger | Result |
| --- | --- | --- |
| [Verify app](.github/workflows/verify.yml) | Push to `main` or pull request | Unit tests, lint, both build variants, instrumentation packaging, and Android emulator tests |
| [Build Debug APK](.github/workflows/debug-apk.yml) | Manual **Run workflow** | A **Debug** prerelease under **Releases**, tagged `debug-<run number>-<attempt>`, plus an Actions artifact retained for 14 days |
| [Release APK](.github/workflows/release.yml) | Manual **Run workflow** on `main`, with `patch`, `minor`, or `major` | Tests/lint, release build, external signing, and a public GitHub release with APK and SHA-256 checksum |

Commit and sync the intended changes before running a manual build. The debug workflow requires `DEBUG_KEYSTORE_BASE64` and reuses that signing key for every build; it fails if the key is missing or invalid. Download `app-debug.apk` directly from its **Debug** prerelease in **Releases**, or extract the `Quintz-debug-<run number>-<attempt>` Actions artifact ZIP. Both include the APK checksum and build details with the signing certificate fingerprint. Debug releases are marked **Pre-release** and do not replace the latest stable release. See [debug signing and updates](docs/debug-signing.md) for setup and migration.

Syncing code does not publish a release. Release signing secrets must be configured first; the workflow rejects missing signing material and checks that `main` still matches the release commit before publishing. The optional `./release.sh [patch|minor|major]` command dispatches the same release workflow through GitHub CLI.

## Debug Diagnostics

Debug builds include a diagnostics button in the app header. Open **DIAGNOSTICS (DEBUG)** and choose **COPY** for the current report, **EXPORT** to share a flight-recorder ZIP through Android's chooser, or **CLEAR** to clear recorded logs.

The recorder stores local diagnostic events, watchdog heartbeats, and available process-exit information. An unobserved interval records a gap with an unknown cause; it does not by itself prove a reboot or continuous monitoring. Release builds disable this recorder and its sharing UI.

Exports redact credential-bearing command arguments and recognized password fields, but include network names, BSSIDs, device details, and connection history. Review the archive before sharing it. Profile recovery payloads are kept separately in encrypted private storage and are not exported.

---

## Architecture & Tech Stack

- **Architecture**: MVVM with unidirectional data flow (UDF) via Kotlin Coroutines and `StateFlow`.
- **UI Framework**: 100% Jetpack Compose with Material 3 and custom industrial cyber-tactical CLI design tokens.
- **Security**: Local network credentials encrypted using AndroidX `EncryptedSharedPreferences`.
- **Profile Recovery**: Encrypted durable recovery records, profile fingerprint/readback checks, and verified transition commits.
- **System Integration**: Shizuku user-service IPC for Wi-Fi profile access, plus bounded shell execution for status, scans, and system integration.

---

## Privacy & Security

- **Local operation**: Wi-Fi steering and telemetry do not require a Quintz server. The app has no analytics or advertising integration. It requests Inter and JetBrains Mono from Google Play services' downloadable-font provider, which may fetch fonts over the network when they are not cached; see [Android's downloadable-font documentation](https://developer.android.com/develop/ui/views/text-and-emoji/downloadable-fonts).
- **Local credentials**: Wi-Fi passwords use `EncryptedSharedPreferences` with an Android Keystore key. Hardware backing depends on the device and is not guaranteed by this configuration. Password saving is refused when secure storage is unavailable.
- **Backup exclusions**: Credential/settings preference files are excluded from Android cloud backup and device transfer. Profile recovery records and flight-recorder journals use private storage excluded from backup.
- **User-controlled sharing**: Debug reports are shared only through an explicit copy/export action; they can contain network and device identifiers.
- **Open Source**: Full source code is available for audit and verification.

---

## Third-Party Open Source Credits & Acknowledgments

Quintz relies on and thanks the following open source projects:

- **[Shizuku](https://github.com/RikkaApps/Shizuku)** by [Rikka Apps / RikkaW](https://github.com/RikkaApps)  
  *Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)*  
  Enables rootless privileged API execution on Android devices.

- **[Android Jetpack & Jetpack Compose](https://developer.android.com/jetpack)** by [Google / AOSP](https://source.android.com)  
  *Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)*  
  Modern UI toolkit, AndroidX lifecycle, architecture components, and crypto security.

- **[Kotlin & Kotlinx Coroutines](https://github.com/Kotlin/kotlinx.coroutines)** by [JetBrains](https://www.jetbrains.com)  
  *Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)*  
  Asynchronous stream processing and state management.

- **[JetBrains Mono](https://www.jetbrains.com/lp/mono/)** by [JetBrains](https://www.jetbrains.com)  
  *Licensed under the [SIL Open Font License 1.1](https://scripts.sil.org/OFL)*  
  Monospace typography used across telemetry readouts.

---

## License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.
