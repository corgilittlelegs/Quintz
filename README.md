# Quintz ⚡

> **Tactical Wi-Fi Band Locker, BSSID Steering & RF Direction Finder for Android.**

[![Platform](https://img.shields.io/badge/Platform-Android%2010%2B%20(API%2029--35)-3DDC84?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?style=flat-square&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Privilege](https://img.shields.io/badge/Privilege-Shizuku%20(No%20Root)-00C853?style=flat-square)](https://shizuku.rikka.app)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)](LICENSE)

---

## Overview

**Quintz** is a high-performance, rootless Wi-Fi utility designed to solve one of Android's most notorious wireless connectivity issues: **sticky 2.4 GHz roaming on dual-band and mesh networks**.

Modern routers broadcast both 2.4 GHz and 5 GHz (or 6 GHz) under a unified SSID. While 5 GHz delivers gigabit speeds and ultra-low latency, Android’s default roaming logic is notoriously conservative. Once your device steps down to 2.4 GHz, it will stubbornly remain connected to the slower, congested band—even when you walk right back next to the router.

Quintz leverages the **[Shizuku](https://shizuku.rikka.app)** privileged API bridge to lock Android directly to the 5 GHz / 6 GHz radio of your network **without needing root access**, while providing real-time AP telemetry, automated fallback protection, and a real-time roaming & RF telemetry monitor.

---

## Key Features

### 🔒 1-Tap 5 GHz Preference (No Root Required)
- Selects a strong 5 GHz / 6 GHz BSSID on your network, then keeps the saved profile available for roaming.
- The preferred-band action is not a permanent BSSID pin: Android and the Wi-Fi firmware can still roam to another BSSID or band when conditions require it.
- Use a specific BSSID lock when you need to keep the profile pinned to one access point.
- Supports WPA2-Personal, WPA3-SAE, Enhanced Open (OWE), and open networks.
- Uses Shizuku to interface safely with Android's system Wi-Fi service.

### 🛡️ Smart Fallback Watchdog
- Runs as a foreground service and checks the connected band while the watchdog is enabled and Shizuku is available.
- The 5 GHz preference leaves the saved profile unpinned so Android or device firmware can roam to 2.4 GHz when needed. The watchdog does not trigger an unlock at a particular 5 GHz RSSI value.
- When the device is connected to the target SSID on 2.4 GHz, the watchdog scans for a same-SSID 5 GHz / 6 GHz BSSID. The default recovery threshold is `-72 dBm`.
- A successful `start-scan` command only means Android accepted the request. Quintz waits up to 12 seconds for evidence that the target network's 5/6 GHz scan result refreshed, and excludes those candidates if it did not.
- Recovery requires the same eligible BSSID in two fresh observations at least 10 seconds apart. If confirmed, Quintz requests a BSSID-specific transition, verifies that the target BSSID became active, then unpins the saved profile again so roaming remains allowed.
- The recovery scan interval starts at about 12 seconds and backs off to 30 seconds when no eligible candidate is found or a recovery attempt fails. Failed switch attempts also receive a retry cooldown that increases from 60 seconds up to 5 minutes.
- Recovery for secured networks requires Quintz to have the network password saved. A missing password, unavailable Shizuku service, stale scan results, or Android/OEM behavior can prevent or delay a switch; recovery is not guaranteed to be seamless.

### ⚙️ How BSSID Pinning Works & Technical Realities
- **The Mechanism**: Quintz configures Android's saved network profile using `cmd wifi connect-network <SSID> <sec> [pass] -b <BSSID> -r <none|persistent>` via Shizuku. This tells Android's `WifiConfigManager` to restrict candidate selection to the designated hardware BSSID.
- **Per-Network MAC Policy**: Supports Device MAC (`-r none`) for router DHCP static reservations or Randomized MAC (`-r persistent`) for privacy.
- **OEM & Firmware Realities**:
  - In Android, Qualcomm Wi-Fi driver firmware (`wlan_driver`) and OEM layers (e.g., Samsung's *Intelligent Wi-Fi* / `SemWifi`) retain autonomous driver-level roaming authority.
  - If RF signal degrades severely or the router issues IEEE 802.11k/v BSS transition management frames, the underlying Wi-Fi chip may autonomously reassociate to 2.4 GHz.
  - **Why the Watchdog Helps**: Because no user-space application can completely override kernel/firmware roaming decisions, Quintz's Watchdog detects when the active connection is on 2.4 GHz and attempts a verified transition to a strong same-network 5 GHz / 6 GHz BSSID when RF conditions permit. It then restores the unpinned profile so normal roaming can continue.

### 📊 Real-Time Roaming & RF Telemetry Monitor
- Real-time rolling oscilloscope canvas tracking active connection RSSI and PHY link speed over time.
- **Multi-AP Roaming Crossover Detection**: Plots candidate BSSIDs under the same network simultaneously to visually expose sticky client behavior and highlight optimal handoff opportunities.
- **Color-Coded RF Quality Bands**: Visual thresholds for Optimal (`> -65 dBm`), Evaluation (`-65 to -75 dBm`), and Roam / Weak (`< -75 dBm`) zones.
- **Automated Handoff Event Tracking**: Drops timestamped event pins whenever band or BSSID transitions take place.
- **Interactive AP Legend**: Instant 1-tap BSSID locking directly from the telemetry monitor.

### 📊 AP Scanner & Channel Analyzer
- Scans and lists all nearby access points, frequencies, channel numbers, and MACs.
- Clear indicators for Wi-Fi standards (**11n**, **11ac**, **11ax**, **Wi-Fi 7**).
- 1-tap **`BIND`** button to pin connection to any specific radio or access point.

### ⚡ Quick Settings Tile
- 1-tap lock and unlock directly from the Android Quick Settings shade without launching the full application.
- Real-time subtitle status shows the active locked channel and band.

### 🔋 Battery & Resource Optimized
- **Zero background shell execution** when the app is idle or minimized.
- Network callbacks are foreground-gated and strictly throttled.
- Canvas graphics allocations are pre-cached, eliminating garbage collection pauses during 60/120 FPS animations.

---

## Prerequisites

- **Android 10** (API 29) through **Android 15 / 16** (API 35+).
- **[Shizuku](https://shizuku.rikka.app)** (v13+ recommended).

### Why Shizuku?
Since Android 10, Google restricted third-party applications from programmatically managing Wi-Fi networks and BSSID associations. Shizuku runs a privileged system service that lets user apps execute authorized system shell commands without modifying Android system files or rooting your device.

**Setting up Shizuku takes ~1 minute on your device:**
1. Install [Shizuku from Google Play](https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api) or GitHub.
2. Open Shizuku and follow the on-screen steps to start it via **Wireless Debugging** (no computer required).
3. Open **Quintz** and tap **GRANT** when prompted.

---

## Building from Source

### Requirements
- **JDK 21**
- **Android SDK** (API 35, Build Tools 35.0.0+)
- **Git**

### Steps

1. **Clone the repository:**
   ```bash
   git clone https://github.com/corgilittlelegs/Quintz.git
   cd Quintz
   ```

2. **Build the release APK:**
   ```bash
   ./gradlew assembleRelease
   ```

3. **Install to your connected device via ADB:**
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

---

## Architecture & Tech Stack

- **Architecture**: MVVM with unidirectional data flow (UDF) via Kotlin Coroutines and `StateFlow`.
- **UI Framework**: 100% Jetpack Compose with Material 3 and custom industrial cyber-tactical CLI design tokens.
- **Sensors**: Hardware sensor fusion (`Sensor.TYPE_ROTATION_VECTOR`) with orientation coordinate remapping across portrait and landscape layouts (`SensorManager.remapCoordinateSystem`).
- **Security**: Local network credentials encrypted using AndroidX `EncryptedSharedPreferences`.
- **System Integration**: Shizuku IPC binder bridge to `cmd wifi` and `dumpsys wifi` system services.

---

## Privacy & Security Guarantee

- **100% Offline**: Quintz contains **no tracking**, **no analytics**, **no ads**, and makes **no internet network requests**.
- **Local Credentials**: Any Wi-Fi passwords entered are saved exclusively on your local device within hardware-backed encrypted storage (`EncryptedSharedPreferences`).
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
