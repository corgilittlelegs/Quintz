# Quintz code audit — 2 October 2026

**Assessment:** the current code has five high-priority correctness defects to address before a production release. Compilation, unit tests, and lint pass, but they do not cover the affected Android service, UI, and transition paths. This is a source-based assessment, not a device certification or a measured battery benchmark.

The report contains **27 findings: 5 P1, 19 P2, and 3 P3**, grouped for execution. Each finding states the triggering condition, the code path, its consequence, and a proposed verification. A conditional trigger is not a claim that the condition occurred on a user's device.

## Scope and evidence boundary

- Audited the current working files, including existing uncommitted and untracked Kotlin changes. HEAD is `12507fdb36afb4f060d1373fad6febb0b196d32e`; HEAD alone does not identify the audited source.
- Reviewed Wi-Fi status and scan acquisition, profile mutations, parsing, coordination, watchdog, Quick Settings tile, preferences and credential storage, UI actions, telemetry, diagnostics, manifests, build configuration, signing scripts, workflows, and the existing tests. README statements were not used as behavioral evidence.
- The graph source changed during final verification; its updated drawing logic was re-read and the affected build/test/lint checks were repeated. Recorded SHA-256 hashes for 74 source/configuration/test files in [the source snapshot](/Users/jaspreet/Documents/Personal/Quintz/docs/audits/2026-10-02-source-sha256.txt). SHA-256 of that manifest: `d7a12aa50867a7812865f858bdf135736ff533c082b279bd16ce169f9a262102`.
- No application source or repository tests were changed by this audit. Audit artifacts and generated build outputs were created. No commit, device installation, release, credential inspection, or deployment was performed.
- Findings derive from source and local compiled-code checks. No external policy, vulnerability database, dependency-age judgment, README promise, previous device observation, or assumed OEM behavior is used to establish a defect.
- **Trace** means a reachable code path establishes the behavior under the stated condition; device occurrence is unverified. **Probe** means a synthetic input reproduced the behavior using the current compiled code. **Cost** means work/allocation is established by source; its CPU, memory, frame-time, or energy impact has not been measured. **Gap** means a concrete validation or build-hardening control is absent in the repository.
- Confidence is high in the stated code facts. Android command semantics, OEM output variants, background scheduling, live exploitability, and production signing compatibility remain outside the validation performed here.

Priority: **P1** — fix before the next production release; **P2** — functional defect, misleading state, resource concern, or readiness gap requiring follow-up; **P3** — lower-priority robustness or efficiency work. Priorities are engineering judgments, not observed incident severity.

## Validation performed

| Check | Result | What it establishes |
|---|---|---|
| `./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --offline --console=plain` | Passed; initial unit-test task was up to date | Local build and lint acceptance of the working files |
| `./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest --offline --console=plain --rerun-tasks` | Passed; all 49 tasks executed | 43 tests in 11 classes passed for debug; the same 43 tests passed for release; zero failures/errors/skips |
| Final snapshot: `./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease --offline --console=plain` | Passed after the graph edit; both test tasks executed | Final source snapshot passes build, both unit suites, and both lint variants |
| Compiled parser probes, outside the app/test source tree | Reproduced A03's empty-parser result and A09/A10's parser cases | Actual outputs from compiled code, using synthetic inputs |
| Debug/release lint XML | 0 errors, 35 warnings each | Lint is passing with warnings; it is not a clean-warning result |
| Local unsigned release APK | Built; 43,808,037 bytes, about 41.78 MiB | Packaging works locally; APK is unsigned and uses local default version inputs |

The lint warnings include dependency suggestions, target-API suggestions, an exemption-request warning, and style/resource warnings. Those suggestions are not treated as proof of a current vulnerability, store rejection, or battery drain. The build also reports Gradle deprecations. Signing with real keys and upgrade installation were not verified.

## Finding matrix

| ID | Group | Priority | Finding | Evidence |
|---|---|---|---|---|
| A01 | Network operations | P1 | First-use MAC choice races the requested bind/preference action | Trace |
| A02 | Network operations | P1 | Saved-profile checks select the first same-SSID profile, ignoring network ID/security | Trace |
| A03 | Network operations | P1 | Preferred-band unpin accepts missing profile evidence | Trace + Probe |
| A04 | Network operations | P1 | Transition success can use an earlier association and ignore absent DHCP | Trace |
| A05 | Credentials/functionality | P1 | Unverified passwords become saved defaults with no correction flow | Trace |
| A06 | Network operations | P2 | Failure rollback reconstructs a profile from the new request | Trace |
| A07 | Network operations | P2 | Open/OWE pinning is supported, but app unlock and connected recovery are inconsistent | Trace |
| A08 | Network operations/UI | P2 | A mismatched saved pin disappears from unlock controls | Trace |
| A09 | Parsing | P2 | SSID parsing changes or rejects significant characters/spacing | Probe |
| A10 | Parsing | P3 | Malformed ages and duplicate selection undermine freshness | Probe |
| A11 | Network operations | P2 | A cross-SSID bind can migrate the active network's pin into the target network | Trace |
| A12 | Preferences/functionality | P2 | Restored secure storage discards newer fallback settings | Trace |
| T01 | Telemetry/UI | P2 | Native graph updates can leave the connected card stale | Trace |
| T02 | Telemetry/UI | P2 | Unknown RSSI is presented as a strong green signal | Trace |
| T03 | Telemetry/UI | P2 | Roam advantage includes radios ineligible for automatic steering | Trace |
| T04 | Scanner/UI | P2 | Old scanner rows survive lost Shizuku access without aging labels | Trace |
| S01 | Security/privacy | P2 | Passphrase visibility and secret state survive dialog completion/cancellation | Trace |
| S02 | Security/privacy | P2 | MAC identity display and success messages do not verify actual identity | Trace |
| B01 | Battery/performance | P2 | Successful scanning returns to a 1.5-second delay without a rate budget | Cost |
| B02 | Battery/performance | P2 | Disconnected recovery polling ignores Wi-Fi-off/no-target preconditions | Trace + Cost |
| B03 | Battery/performance | P2 | Hidden telemetry still runs one-second state processing | Cost |
| B04 | Performance/reliability | P2 | Shell work lacks bounded aggregate resources and end-to-end deadlines | Trace + Cost |
| B05 | Performance/UI | P3 | Scanner renders every AP row eagerly | Cost |
| B06 | Battery/performance | P2 | Debug diagnostics perform synchronous journal work and share one export lock | Cost; debug only |
| R01 | Production readiness | P2 | Automated tests omit UI/services/transitions; CI runs only when dispatched | Gap |
| R02 | Production readiness | P3 | Release shrinking/optimization is disabled | Gap + measured artifact size |
| R03 | Security/release readiness | P2 | Release actions use mutable tags with a job-wide write token | Gap; no compromise alleged |

## Group A — Network operations, credentials, and parsers

### A01 — P1: First-use MAC selection starts two competing operations

**Evidence:** [MainScreen.kt:1164](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainScreen.kt:1164), [MainViewModel.kt:572](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:572), [WifiController.kt:521](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:521), [WifiOperationCoordinator.kt:15](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiOperationCoordinator.kt:15).

When no MAC preference exists, the dialog calls `setMacPolicy(...)`, then immediately invokes `pendingLockAction`. The setter launches a coroutine, refreshes status, and performs a network operation before saving the selected preference. The pending action launches separately and does not receive the selected policy. It resolves the old saved/default policy. Both operations compete for a coordinator that rejects overlap rather than queues it.

The code permits an interleaving in which the bind uses the default Device MAC after Randomized MAC was selected, or one action fails because the other holds the operation lock. This is a scheduling defect established by the lack of ordering; which interleaving occurs on a device is unverified.

**Remediation:** pass the selected policy into the pending action and execute the requested transition once. Await the result before changing persisted preferences.

**Verify:** exercise first-use PIN/BIND/PREFER for both the active SSID and another SSID, with delayed status responses; assert exactly one transition, the chosen `-r` argument, and matching persistence.

### A02 — P1: Profile evidence is selected by SSID rather than active profile identity

**Evidence:** [WifiController.kt:200](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:200), with the same pattern at lines 432–437, 466–472, 750–756, and 881–886; contrast [WifiParser.kt:95](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiParser.kt:95).

Every saved-profile check takes the first line containing `PROVIDER-NAME:` and the quoted SSID. It does not check profile ID or security type. The network-ID parser already recognizes that one SSID can have separate WPA2 and WPA3 saved profiles, but that distinction is discarded in pin/unpin verification.

For two profiles with the same SSID, the first profile can be pinned while the active one is unpinned, or vice versa. The code can therefore report/migrate the wrong pin, fail a correct operation, or accept unpinning based on a different profile. This follows from the selection predicate; it does not assert that a particular device currently has duplicate profiles.

**Remediation:** parse the saved configuration into records and match the exact network ID/security identity. Treat ambiguous or missing identity as unknown, and avoid persistent target migration from unknown profile state.

**Verify:** fixtures with same-name WPA2/WPA3 profiles in both orders, distinct BSSID pins, and a missing active ID must produce the same correct result or an explicit unknown result.

### A03 — P1: `ensureProfileUnpinned` turns missing evidence into success

**Evidence:** [WifiController.kt:430](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:430), [WifiParser.kt:86](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiParser.kt:86).

The helper substitutes `""` when no profile line is found. `parseLockedBssid("")` returns `(false, null)`. The helper then computes `unpinResult.isSuccess && !isStillPinned`, without requiring successful profile inspection, an actual profile line, or an explicit unpinned field.

Consequently, successful `add-network` plus failed/empty `dumpsys` evidence is sufficient for the preferred-band path to claim unpin success. A same-BSSID operation can persist `PREFER_5_GHZ` and return success without proving roaming is enabled. The standalone `unlockToAuto` method checks that the line is nonempty; this helper does not.

**Remediation:** use a three-state parser: pinned, explicitly unpinned, unknown. Require a successful inspection of the exact profile and explicit unpinned evidence.

**Verify:** failed query, empty output, missing BSSID field, malformed line, and ambiguous profile must all fail verification. Explicit `BSSID: any/null` on the matched profile should pass.

### A04 — P1: Final success does not require the final connection to satisfy the transition

**Evidence:** [WifiController.kt:733](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:733), lines 737–775, and [WifiController.kt:910](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:910).

`activeMatchesTarget` is computed before profile inspection/unpinning. Those operations refresh status, but the final predicate still uses the earlier Boolean. A further refresh after preferences are saved is also ignored when returning success. A connection change during those intervening steps can therefore coexist with a successful result and committed target mode.

Separately, `awaitConnectionSettled` checks for a nonempty/nonzero IP while waiting, but returns the last status at timeout. Its callers can subsequently accept matching SSID/BSSID and profile state without requiring that IP condition. The UI explicitly describes DHCP verification, but DHCP is not a final success requirement.

**Remediation:** define one final success predicate over one freshly observed connection/profile snapshot, including the intended IP requirement. Persist mode only after that predicate succeeds. Represent association success and IP readiness separately if the product intends to support both.

**Verify:** target association followed by a roam/disconnect during unpinning, and target association with no IP at timeout, must not produce the current full-success result.

### A05 — P1: Failed authentication can trap the UI behind a saved wrong password

**Evidence:** [WifiController.kt:567](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:567), [MainScreen.kt:195](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainScreen.kt:195), [MainScreen.kt:208](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainScreen.kt:208), [Preferences.kt:109](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/data/Preferences.kt:109).

The submitted password is saved before connection/profile verification. A later failure leaves it saved. Both bind and prefer flows automatically reuse any nonempty saved password and open the password dialog only when none is saved. `removePassword` has no caller anywhere in application source, and there is no replace/forget-password UI.

Under a failed password submission, retries continue with that same password. The same problem arises if an existing network's password changes. This finding concerns persistence and recovery logic, not encryption strength or a verified password leak.

**Remediation:** retain new credentials provisionally until success, preserve the previously verified value on failure, and provide a deliberate replace/forget-password flow.

**Verify:** a wrong first password must allow correction on the next attempt; a failed replacement must retain the previous verified credential.

### A06 — P2: Rollback does not restore the original saved configuration

**Evidence:** [WifiController.kt:527](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:527), [WifiController.kt:656](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:656), [WifiController.kt:777](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:777).

Before forgetting the active profile, the code captures previous target mode and pinned BSSID. It does not capture the original profile or previous credential/MAC/security configuration. Failure recovery calls `add-network` with the **new request's** password, security, and MAC flag, optionally adding the previous BSSID.

Thus the rollback cannot establish equality with the original configuration. In particular, a failed request containing a wrong password or changed MAC choice is reused for restoration. Additional saved-profile attributes are neither captured nor replayed. The report does not assume which attributes any device actually has.

**Remediation:** avoid destroying the saved profile where possible. Otherwise preserve the fields needed for an exact supported rollback and verify restoration; accurately describe unsupported restoration limits.

**Verify:** failed same-SSID handoff after credential/MAC changes must restore the previous supported configuration, with failed restoration surfaced separately from ordinary transition failure.

### A07 — P2: Open/OWE support has incompatible unlock and recovery paths

**Evidence:** [MainViewModel.kt:757](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:757), [WifiController.kt:824](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:824), [WifiController.kt:1042](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:1042), [WatchdogService.kt:354](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/service/WatchdogService.kt:354), [WifiSecurityPolicy.kt:70](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiSecurityPolicy.kt:70).

Manual radio selection explicitly supports Open/OWE pinning. The app's UNPIN flow calls `unlockToAuto` without a security argument. The fallback detector recognizes only WPA2/WPA3 and otherwise returns `wpa2`. With no saved password, that unlock path returns false instead of unpinning the open/OWE profile. The tile correctly supplies `open`/`owe`, so behavior differs by entry point.

Connected PIN recovery also runs `allowsTrustedAutomaticSwitch`, which accepts only WPA2/WPA3, and later requires a saved password. Disconnected PIN recovery explicitly supports trusted Open/OWE radios. An accepted open pin therefore has inconsistent recovery depending on whether another association is still active.

**Remediation:** pass the verified security type through every unlock path. Handle restoration of an explicitly trusted pin separately from automatic preferred-band selection, while retaining the exact-radio restrictions for Open/OWE.

**Verify:** Open and OWE PIN → UNPIN from both app layouts and tile, plus connected and disconnected pin restoration.

### A08 — P2: A saved pin that differs from the active BSSID has no normal UNPIN control

**Evidence:** [WifiController.kt:218](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:218), [WifiModels.kt:46](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/model/WifiModels.kt:46), [MainScreen.kt:628](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainScreen.kt:628), [ConnectedHeroSection.kt:399](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/components/ConnectedHeroSection.kt:399).

`isLockedToBssid` requires the saved pin to equal the active BSSID. In PIN mode with a mismatch, preferred-band flags are also false. The primary control uses those flags to choose PREFER rather than UNLOCK. Scanner rows require `isLockedToBssid` before showing PINNED/UNPIN, so the saved target row also loses UNPIN.

The code models this unexpected association state in watchdog recovery, but the UI hides the saved intent needed to clear it. The tile uses persisted target mode and can unlock, so this is also an entry-point inconsistency.

**Remediation:** expose requested mode and observed association separately. Offer UNPIN whenever a pin is saved, including when it is not currently satisfied.

**Verify:** saved pin X with active BSSID Y must show the requested pin, its mismatch, and an action that clears it.

### A09 — P2: SSID identity is corrupted by string trimming and table splitting

**Evidence:** [WifiParser.kt:9](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiParser.kt:9), lines 22, 40–45, 95–100 and 149–155; [WifiController.kt:80](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:80).

Compiled probes show status SSID `"  Lab  "` becomes `Lab`, and an escaped quote in `Lab\"West` truncates the parsed identity to `Lab\`. The saved-network parser splits on any run of two or more spaces: `Lab West` succeeds, while `Lab  West` fails exact active-ID lookup. Scan SSID reconstruction also trims edge whitespace.

SSID identity is then used in equality checks, credential keys, trusted-radio keys, and shell arguments. The network-ID parser safely aborts an ambiguous handoff, but these names cannot be handled consistently by the current parser.

**Remediation:** decode the actual quoted representation and preserve SSID data. Parse table columns by their structural boundaries rather than whitespace occurring inside the SSID.

**Verify:** leading/trailing/internal repeated spaces, quotes, backslashes, Unicode, and same-name security variants round-trip exactly across status, scans, saved networks, and preference keys.

### A10 — P3: Scan numeric parsing is not fail-closed for all malformed ages

**Evidence:** [WifiParser.kt:139](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiParser.kt:139), [WifiParser.kt:181](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiParser.kt:181).

Actual compiled outputs accept age `-5`, convert `NaN` to `0`, and convert `-Infinity` to `Long.MIN_VALUE`; all pass the upper-bound-only age check. These values can also produce invalid/future observation timestamps when converted later. Age `8.999` becomes `8`, widening an eight-second freshness boundary by truncation.

Duplicate BSSIDs are selected by strongest RSSI, not newest observation. A synthetic pair with age 14/-40 dBm and age 1/-70 dBm retains age 14 and discards the fresh reading. A target check can then reject a radio despite its fresh duplicate being present.

These are reproduced input-handling weaknesses, not evidence that Android currently emits such rows or that an AP controls shell age fields.

**Remediation:** require finite, nonnegative ages; preserve sufficient timestamp precision; deduplicate by newest observation before comparing signal strength.

**Verify:** malformed/nonfinite/negative ages fail closed, and duplicates retain the newest valid observation regardless of RSSI.

### A11 — P2: A cross-SSID bind can persist a pin belonging to another network

**Evidence:** [WifiController.kt:534](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:534), [Preferences.kt:198](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/data/Preferences.kt:198).

`lockToBssidInternal` calls `getOrMigrateWifiTargetMode` for the requested SSID, but supplies the active connection's pinned BSSID without requiring that the active SSID matches the requested SSID. The migration persists PIN mode whenever that argument is nonempty.

If the device is pinned on network A and a user binds a radio on previously unmigrated network B, B can be assigned A's pin before the target scan/security/credential checks. If the request then fails or aborts, no successful mode write replaces that incorrect B preference. The watchdog can subsequently see a target B/BSSID-A combination that the user never requested.

**Remediation:** only supply observed profile evidence when its SSID matches the preference being migrated. Avoid persisting a migration from unrelated pre-request state.

**Verify:** pinned A → failed bind B leaves B unchanged; successful bind B records only B's verified target.

### A12 — P2: Secure-storage recovery can discard newer fallback preferences

**Evidence:** [Preferences.kt:21](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/data/Preferences.kt:21), lines 35–39 and 44–76.

If encrypted preferences cannot be created, non-secret settings are written to the ordinary fallback store. On a later initialization with secure storage available, migration sees an already-present secure key, marks the fallback key for cleanup, and retains the secure value. It does not compare which value was changed later.

For example, an existing secure Watchdog-enabled value, a fallback-mode change to disabled, and subsequent secure-storage recovery restores enabled and deletes the newer disabled choice. The conditional state is explicit in the constructor/migration logic; the audit does not assert that Keystore failed on a particular device. Passwords remain excluded from insecure fallback writes.

**Remediation:** define and record precedence/versioning for fallback edits, or keep non-secret settings in one durable store independently of secret-storage availability.

**Verify:** change each affected setting during simulated secure-storage unavailability, restore storage, and verify the most recent intended value survives.

## Group T — Telemetry and displayed state

### T01 — P2: Graph sampling and the connected card can diverge indefinitely

**Evidence:** [MainViewModel.kt:221](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:221), [MainViewModel.kt:237](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:237), [MainViewModel.kt:126](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:126).

Active, unpaused graph sampling suppresses the ordinary controller status poll. When native telemetry identity is available, readings are stored in `nativeTelemetryObservation`, without updating `controller.status`. Capability callbacks refresh controller status only for frequency/BSSID changes or a disconnected cached state; RSSI/link-speed changes alone do not qualify.

On the wide layout, the graph can update while the connected card continues showing its old RSSI/link speed. The same split leaves native and controller connection state on separate update paths. This is especially visible because both panes are displayed together.

**Remediation:** share the observed metrics or maintain an explicit periodic verified-status cadence alongside graph sampling. Keep profile/security evidence distinct from inexpensive native metric updates.

**Verify:** a stable BSSID/frequency with changing RSSI/link speed must update both panes without changing tabs or relying on unrelated callbacks.

### T02 — P2: A missing signal measurement becomes green `0 dBm`

**Evidence:** [WifiController.kt:85](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:85), [MainViewModel.kt:390](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:390), [CliPrimitives.kt:212](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/components/CliPrimitives.kt:212), [WifiGraphView.kt:354](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/graph/WifiGraphView.kt:354).

Zero is used as the unavailable-RSSI sentinel. `CliSignalBars` classifies every value `>= -55` as four green bars, including zero. The graph resets unavailable signal to zero while retaining `isConnected`, and its RSSI pill also treats zero as green.

**Remediation:** represent unavailable signal explicitly and render `Unavailable`/`--` with no strength classification.

**Verify:** connected status with RSSI 0/-127 never produces a strong-green indication or a fictitious measurement.

### T03 — P2: “Roam advantage” does not mean the candidate is eligible for recovery

**Evidence:** [MainViewModel.kt:418](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:418), lines 452–456; compare [WatchdogService.kt:345](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/service/WatchdogService.kt:345).

Telemetry candidates require matching SSID, a different BSSID, and visibility/freshness. They do not require matching security, a trusted radio, the desired band/pin, or the recovery threshold. `CandidateSample` does not carry that eligibility evidence. The best candidate's RSSI can therefore drive the ROAM ADVANTAGE badge while the watchdog correctly refuses that radio.

**Remediation:** label the value as a signal comparison, or carry eligibility and rejection reasons alongside it. Use the same policy when presenting a candidate as actionable.

**Verify:** stronger untrusted, incompatible-security, and wrong-target-band radios must be visibly distinguishable from an eligible recovery target.

### T04 — P2: Scanner measurements have no displayed expiration after access loss

**Evidence:** [WifiController.kt:277](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:277), [MainViewModel.kt:278](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:278), [RadioRow.kt:91](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/components/RadioRow.kt:91).

When Shizuku is unavailable, `scanRadios` returns before clearing radios or updating scan metadata. Polling stops requesting scans, but existing rows remain. Rows display stored RSSI without observation age or a stale/offline state; bind buttons only check `isOperating`.

Consequently, previously fresh rows can remain on screen indefinitely after privileged access disappears. Revalidation inside the controller still protects a later mutation; this finding does not claim stale rows can bypass that protection.

**Remediation:** mark cached rows unavailable/stale using elapsed age and acquisition state, and disable unavailable actions or explain the required access.

**Verify:** losing Shizuku after a successful scan must visibly age/disable the old measurements without needing another scan.

## Group S — Security and privacy

### S01 — P2: The passphrase dialog does not reliably reset secret/visibility state

**Evidence:** [MainScreen.kt:112](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainScreen.kt:112), lines 201–203, 221–224, 1010–1034, 1038–1057 and 1143–1149.

The show-password toggle is remembered outside the dialog. Submit and the CANCEL button do not reset it; opening a new dialog clears text but does not force masking. A user who previously enabled visibility can therefore type a new passphrase into an already unmasked field. Dialog completion/cancellation also leaves the prior `passwordInput` string retained in Compose state until another opening or composition disposal.

**Remediation:** make each credential interaction default to masked, clear the text and target on every terminal path, and retain a submitted value only for the minimum lifetime of its pending operation.

**Verify:** show password → Cancel/Submit → reopen must start masked and empty, including backdrop/back dismissal and a subsequent MAC-dialog cancellation.

### S02 — P2: MAC identity is a stored preference presented as actual identity

**Evidence:** [ConnectedHeroSection.kt:300](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/components/ConnectedHeroSection.kt:300), [MainViewModel.kt:594](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:594), [WifiController.kt:733](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:733).

The MAC IDENTITY badge reads only the saved preference and defaults to Device MAC when none exists. The model/status parser does not carry the active MAC or observed randomization policy. The “MAC mode changed” message depends on SSID/BSSID/profile transition success, without comparing the requested policy with observed profile/MAC state.

The code cannot establish the actual privacy identity it displays, including before Quintz has applied any policy. This does not claim a particular device ignored `-r`; it establishes that such disagreement is not checked.

**Remediation:** label an unverified value as a preference, query the effective policy/identity when supported, and distinguish requested, applied, and observed state.

**Verify:** unset preference, external policy change, and a command outcome without policy evidence must not be presented as verified Device/Randomized MAC.

## Group B — Battery, performance, and resource reliability

### B01 — P2: Scan acquisition has no steady-state request budget

**Evidence:** [WifiScanPolicy.kt:22](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiScanPolicy.kt:22), [MainViewModel.kt:278](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:278), [WifiController.kt:321](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:321), [MainScreen.kt:265](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainScreen.kt:265).

After every successful scan, the next delay is 1,500 ms. A cycle normally requests a baseline listing, `start-scan`, and one or more result listings. Failure backoff resets after success. There is no per-minute admission budget, activity-adaptive interval, or explicit battery-saving sampling choice. The scanner is active on every wide layout, including when only the graph pane is selected.

This establishes a high configured request cadence and repeated shell/scan work. It does not establish actual hardware scan frequency, Android throttling behavior, or a battery-drain percentage.

**Remediation:** set an explicit acquisition budget and steady-state cadence, share acquisition between UI/watchdog, and expose faster sampling when it serves a deliberate diagnostic need.

**Verify:** measure request/actual-scan counts and energy under stable, fallback, paused, and failed-scan conditions before selecting intervals.

### B02 — P2: Disconnected polling is not gated by Wi-Fi-off or actionable target state

**Evidence:** [WatchdogService.kt:68](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/service/WatchdogService.kt:68), [WatchdogService.kt:101](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/service/WatchdogService.kt:101), [WatchdogService.kt:289](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/service/WatchdogService.kt:289), lines 510–520.

The network callback checks whether Wi-Fi was intentionally switched off, but the periodic loop does not. A disconnected status enters the 20-observation recovery routine regardless of saved AUTO/no-target state. `hasWatchdogTargets` is checked only in the connected, resolved-SSID branch. After disconnect handling, the loop schedules another check with a 6-second delay.

Thus an enabled service can repeatedly run status/shell checks while Wi-Fi is off or no target can be acted upon. A PIN/PREFER branch can also attempt recovery after that observation window without rechecking the user's Wi-Fi-off intent. No claim is made that a particular command actually re-enabled Wi-Fi.

**Remediation:** gate the entire recovery path on current Wi-Fi-enabled state and an actionable saved mode; recheck before mutation. Stop or wait cheaply when there are no saved targets.

**Verify:** Wi-Fi off, no targets, and AUTO-only disconnected states produce no repeated recovery scans/mutations and no 20-query polling bursts.

### B03 — P2: One-second telemetry processing continues while the graph is hidden

**Evidence:** [MainViewModel.kt:221](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:221), [MainViewModel.kt:237](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:237), [MainViewModel.kt:340](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainViewModel.kt:340).

`startForegroundPolling` always starts the telemetry job. Even with `telemetryRequested == false`, the job updates the telemetry clock and calls `recordTelemetrySample` every second. That method builds candidate maps, sorts metadata, copies sample collections, and filters RSSI history even if no graph is composed. A separate collector also calls it on controller status updates.

The native telemetry query is correctly conditional and jobs stop on activity pause. The remaining issue is foreground processing for a hidden consumer, not an established background-drain claim.

**Remediation:** gate expensive telemetry processing on graph visibility or an explicit recording request, and avoid duplicate processing of the same observation.

**Verify:** controls-only use performs the desired status polls without one-second graph-state work; graph visibility resumes sampling correctly.

### B04 — P2: Per-command timeouts do not bound resource use or entire operations

**Evidence:** [ShizukuManager.kt:31](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/shizuku/ShizukuManager.kt:31), [ShizukuManager.kt:175](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/shizuku/ShizukuManager.kt:175), [WifiScanCoordinator.kt:31](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiScanCoordinator.kt:31), [WifiController.kt:333](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:333), [WifiController.kt:910](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/WifiController.kt:910).

Each shell command submits stdout, stderr, and process-wait workers to an unbounded cached pool. Output accumulates in StringBuilders without a byte cap. There is no process-wide shell admission limit, and coroutine cancellation is not passed into this synchronous runner.

The scan coordinator holds its mutex while waiting/issuing commands; queued request waiting has no deadline. A scan's 12-second deadline is checked around shell calls that have their own 12-second timeout. Connection waits similarly call a potentially slow serialized status refresh before rechecking their deadline. The watchdog's “19 seconds” describes its delays, not the actual duration of 20 shell-backed observations.

The exact thread count, peak bytes, and elapsed delays require measurement, but the absence of aggregate limits is explicit in code.

**Remediation:** bound concurrency and captured output; make cancellation own process cleanup; carry a monotonic deadline across queueing and nested commands rather than restarting the timeout per step.

**Verify:** stalled command, excessive output, multiple callers, and cancellation at queue/start/read stages leave bounded workers/output and release all coordinators within the intended deadline.

### B05 — P3: AP lists use eager composition rather than a lazy list

**Evidence:** [MainScreen.kt:623](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainScreen.kt:623), [MainScreen.kt:858](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainScreen.kt:858).

Both scanner layouts use a scrolling `Column` and `forEachIndexed`, constructing every row. Work and layout scale with the full AP list rather than visible rows. There is no keyed lazy container. This is an established scaling cost, not a measured frame-drop incident.

**Remediation:** use a keyed `LazyColumn` or equivalent lazy item container.

**Verify:** compare composition/layout allocations and scrolling with large synthetic AP sets while scans update.

### B06 — P2: Debug logging performs synchronous disk work on event callers

**Evidence:** [DiagnosticLogger.kt:64](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/DiagnosticLogger.kt:64), [FlightJournal.kt:54](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/FlightJournal.kt:54), [DiagnosticLogger.kt:175](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/core/DiagnosticLogger.kt:175), [MainScreen.kt:188](/Users/jaspreet/Documents/Personal/Quintz/app/src/main/java/com/quintz/wifi/ui/MainScreen.kt:188).

Every debug event formats timestamps, traverses deque size, acquires the journal lock, opens/appends/closes a file, and prunes directory segments. UI events and network callbacks call logging synchronously. Export holds that same journal lock while writing/compressing the retained journal, which is configured for up to 40 MiB.

The app can therefore make event callers wait for file work/export. This also adds I/O to debug overnight monitoring and makes debug measurements a different workload from release, where this logger is disabled. No ANR or energy magnitude is asserted.

**Remediation:** use a bounded background writer and snapshot export outside the event-write lock while preserving ordering and crash evidence.

**Verify:** log throughput, main-thread blocking, export latency, overflow handling, and crash persistence; compare battery measurements by build type.

## Group R — Production and release readiness

### R01 — P2: Passing unit tests do not exercise the production-critical orchestration

**Evidence:** [the unit-test source tree](/Users/jaspreet/Documents/Personal/Quintz/app/src/test), [release.yml:3](/Users/jaspreet/Documents/Personal/Quintz/.github/workflows/release.yml:3), [release.yml:114](/Users/jaspreet/Documents/Personal/Quintz/.github/workflows/release.yml:114), [debug-apk.yml:3](/Users/jaspreet/Documents/Personal/Quintz/.github/workflows/debug-apk.yml:3).

The 43 tests cover parsers, security/target policies, operation-lock admission, telemetry helpers, tile-spec matching, sanitization, and journal behavior. They do not instantiate/exercise `WifiController`, `MainViewModel`, `WatchdogService`, `TileService`, credential dialogs, or the full mutation/rollback sequence. No instrumentation test sources were found.

Both repository workflows are `workflow_dispatch` only. Release dispatch correctly runs tests and lint, but ordinary pushes/PRs have no automatic test workflow in this repository. External branch protection is not inferred.

**Remediation:** add targeted orchestration tests with controllable shell/status responses and a small UI/device suite for the critical flows; run the checks automatically on code changes while preserving manual release publication.

**Verify:** A01–A08's failure/interleaving cases and service restart/cancellation paths are exercised before sign-off. Existing pure tests remain valuable and should be retained.

### R02 — P3: Release packaging skips code/resource shrinking

**Evidence:** [app/build.gradle.kts:55](/Users/jaspreet/Documents/Personal/Quintz/app/build.gradle.kts:55), [the generated unsigned APK](/Users/jaspreet/Documents/Personal/Quintz/app/build/outputs/apk/release/app-release-unsigned.apk).

`isMinifyEnabled = false`, with no resource shrinking enabled. The local unsigned release APK is about 41.78 MiB. Declaring ProGuard files does not activate them when minification is disabled.

This is an optimization gap, not proof of slow startup, excessive live memory, or a specific achievable size reduction. Reflection-based Shizuku access must be preserved and validated if shrinking is enabled.

**Remediation:** compare a safely configured optimized release and retain required reflection/manifest entry points.

**Verify:** APK size, startup/behavior, and Shizuku/tile/watchdog functionality using the optimized artifact before adopting it.

### R03 — P2: Release execution trusts mutable action tags under broad token permissions

**Evidence:** [release.yml:20](/Users/jaspreet/Documents/Personal/Quintz/.github/workflows/release.yml:20), lines 30, 36 and 251–254; [debug-apk.yml:16](/Users/jaspreet/Documents/Personal/Quintz/.github/workflows/debug-apk.yml:16).

The release workflow references `actions/checkout@v4`, `actions/setup-java@v5`, `actions/upload-artifact@v4`, and `softprops/action-gh-release@v2`, without immutable commit SHAs. `contents: write` is granted across the release job, including build and external-action steps, rather than a separate publication job.

These are concrete supply-chain/release-hardening gaps: the action implementation is not fixed by the repository, and write permission exceeds the stages that need publication access. There is no code evidence here of a compromised action, stolen token, or exposed signing key.

**Remediation:** pin reviewed action revisions and narrow publication privileges, ideally separating the verified build artifact from publication.

**Verify:** required release safeguards and signing still work with pinned revisions and the minimum job permissions. Do not publish a release just to validate this audit.

## Existing safeguards confirmed in source

These should not be re-reported as missing fixes:

- Wi-Fi mutation entry points share `WifiOperationCoordinator`; overlapping profile operations are rejected and releases occur in `finally`.
- Current shell mutation arguments are single-quote escaped. The earlier Quick Settings removal pattern that interpolated a whole tile-settings list is absent; removal uses an escaped component and verifies the resulting tile list.
- Password saving requires encrypted storage; Keystore construction failure does not fall back to plaintext password saving. Legacy plaintext password cleanup exists. Both preference files are excluded from cloud/device backup rules.
- Automatic steering requires a trusted exact radio and compatible security. Same-name secure-to-open downgrades are rejected. Open/OWE automatic preferred-band switching is deliberately excluded. This is not the same as enforcing a BSSID allowlist on Android's normal roaming after a profile is unpinned.
- A same-SSID destructive handoff requires exact active network ID, SSID, security, and current BSSID revalidation; it refuses an unresolved profile rather than blindly forgetting by name.
- Targeted scans require target observations and bounded acquisition polling, not just a successful start-scan exit or broadcast. Ordinary malformed text ages are rejected; A10 documents the numeric cases that remain.
- Telemetry has bounded retained history, candidate-age checks, stable colors, and graph-segment breaks for BSSID changes/gaps. These safeguards do not resolve T01–T04.
- Debug diagnostics are private, bounded, located under `noBackupFilesDir`, and exported through a debug-only non-exported FileProvider with a read grant. Credential-bearing command arguments are excluded from routine command logs.
- Watchdog callbacks are unregistered and coroutine scopes are cancelled during service destruction. Foreground UI polling jobs stop on activity pause.
- Watchdog service is not exported; the tile is protected by the system tile-binding permission, and the Shizuku provider declares a privileged permission. Exported components alone are not treated as proof of an exploit.
- Release publishing requires tests/lint, newer main-line source, version/tag guards, externally supplied signing keys/lineage, signature checks for selected SDK levels, checksums, and a final remote-main check. No signing-key fallback exists in the current release build configuration.

## Execution order and production decision

| Execution group | Findings | Release implication |
|---|---|---|
| 1. Make transitions verifiable and ordered | A01–A05 | Resolve before production release; add tests for failed evidence, credentials, and interleavings |
| 2. Complete supported network behavior | A06–A12 | Restore rollback accuracy, Open/OWE parity, pin clearing, exact identities, and preference migration |
| 3. Make state and privacy claims accurate | T01–T04, S01–S02 | Prevent stale/unknown measurements and unverified privacy settings from being presented as current facts |
| 4. Control acquisition and resource work | B01–B06 | Bound requests, waits, hidden work, and debug I/O; then measure on a device |
| 5. Establish release validation and hardening | R01–R03 | Cover orchestrated flows, preserve manual publication, and verify optimized/signed artifacts separately |

**Production decision:** the current working tree builds and passes its existing tests, but it is not ready for an unconditional production sign-off. Five P1 findings concern actions the app presents as successfully applying network intent, and they are not exercised by the existing test suite. A controlled diagnostic build is a separate decision from a production release.

A source-only audit cannot certify continuous overnight monitoring, DHCP/network reachability on actual hardware, OEM shell-output coverage, upgrade compatibility of the signing lineage, battery use, or live exploitability. Those are explicitly unverified gates, not inferred failures. Dependency CVEs and current app-store requirements were not assessed because this report is restricted to code-derived evidence.

## Appendix — Compiled-code probe results

The probe called the current `WifiParser` compiled classes from a Java harness in `/private/tmp/quintz-source-audit`; no app code or tests were patched. Inputs were synthetic, with no real network credentials.

```text
empty_profile=(false, null)
status_spaces_expected=[  Lab  ], actual=[Lab]
status_quote_expected=[Lab"West], actual=[Lab\]
network_single_space=17
network_double_space=null
age_input=-5, accepted=1, parsed_age=-5
age_input=NaN, accepted=1, parsed_age=0
age_input=-Infinity, accepted=1, parsed_age=-9223372036854775808
age_input=8.999, accepted=1, parsed_age=8
duplicate_selected_age=14, rssi=-40
```

The two network rows used the same ID/security and differed only between `Lab West` and `Lab  West`. The duplicate test used identical BSSID/security with age 14/-40 dBm and age 1/-70 dBm. The harness is [available locally](/private/tmp/quintz-source-audit/AuditLogicProbe.java); its lifetime is temporary. Results above are preserved in this report.
