# Bridge Share Direct ↔ Universal Radio-Helper Integration — Task Journal

## CURRENT STATE / NEXT STEP
- **Goal:** wire `bridge-share-direct` into the ONE universal `radio-helper` APK for
  silent radio enable/restore (multi-app helper policy) + make an NFC tap turn on BOTH
  Wi-Fi and Bluetooth IMMEDIATELY for a faster start.
- **DONE (code, 2026-06-25):** (a) base integration — coordinator + client + manifest +
  send/receive hooks + signing fix. (b) NFC fast-start — `NfcLaunchActivity` now silently
  enables BOTH radios on tap (no BT-dialog / 2nd-tap); coordinator switched to RADIO_BOTH
  (BLE trigger needs BT, transfer needs Wi-Fi). Both builds BUILD SUCCESSFUL; APK at repo
  root. Helper BT-enable confirmed from `ShareRadioSession.prepare()` source.
- **NEXT EXACT STEP:** user runs the ON-DEVICE TEST SCRIPT below on a helper-paired phone
  (esp. test 9: Wi-Fi+BT both OFF → tap → both flip on silently, no dialog, receive starts).
  On-device behaviour UNVERIFIED here (no device/helper in this env).

## NFC FAST-START (added 2026-06-25 (b))
- Earliest receiver NFC landing point = `NfcLaunchActivity` (NDEF_DISCOVERED, host
  `bridgeshare.app`). OLD: `ensureBluetoothOn()` popped the system BT dialog + returned
  false if BT off → needed a 2nd tap; Wi-Fi never touched. NEW: `startNfcReceive()` →
  `BridgeRadioCoordinator.acquireReceiveForNfc(this)` fires silent BOTH-radio enable +
  heartbeat, then `ReceiveService.nfcReceive()` immediately. Helper-absent → legacy
  BT-dialog fallback. Funnel covers onCreate + onNewIntent.
- Coordinator now requests `RADIO_BOTH` for ALL flows (send + receive) — corrected from
  RADIO_WIFI-only (which would've left BT off and stalled the BLE handshake).
- VERIFIED: helper `ShareRadioSession.prepare()` (bada-fork) enables BT when RADIO_BT set
  (line ~105) and restores only what it turned on (line ~133).

## What was changed (all 2026-06-25)
| # | File | Change |
|---|------|--------|
| 1 | `build.gradle` | signingConfigs.debug → standard `~/.android/debug.keystore` (alias androiddebugkey / pass android). **Required**: BIND_RADIO is a signature perm; old `keystore/debug.jks` (alias `bridge`) fingerprint `20:9E:11:48…` did NOT match the helper's `EE:B7:99:52…`. |
| 2 | `src/main/java/dev/superdrop/radiohelper/client/RadioHelperClient.kt` | NEW — verbatim copy of the canonical drop-in client from oconnect-bridge (pkg `dev.superdrop.radiohelper.client`). |
| 3 | `src/main/java/com/bridge/share/radio/BridgeRadioCoordinator.kt` | NEW — refcounted Wi-Fi gate (object singleton). Owners SEND + RECEIVE; `prepareForShare(RADIO_WIFI)` / `transferFinished`; 5s heartbeat; graceful no-op if helper absent. Adapted from oconnect's BT coordinator → **Wi-Fi** (this app's link is Wi-Fi; trigger is BLE). |
| 4 | `src/main/AndroidManifest.xml` | + `uses-permission BIND_RADIO` + `<queries>` for `dev.superdrop.radiohelper` and `.debug`. |
| 5 | `EngineSendController.java` | acquireSend(appCtx) at top of `sendTo()`; releaseSend() in `stop()`. |
| 6 | `ReceiveService.java` | acquireReceive(this) after both successful `startForeground` (normal armed + NFC-receive); releaseReceive() in `fullTeardown()` (single disarm funnel for OFF/expiry/onDestroy). |

## VERIFIED facts (where proven)
- Helper APK `dev.superdrop.radiohelper` signer SHA-256 = `eeb799…1cf7` (apksigner on
  `bada-fork/radio-helper-debug.apk`), == standard `~/.android/debug.keystore` cert.
- oconnect-bridge (works w/ helper) uses that same standard key; bridge-share-direct
  previously used a DIFFERENT key (`209e1148…`, keytool) → would have been bind-denied.
- bridge-share-direct: Kotlin 1.9.24 enabled; minSdk 29 / target 35 (build.gradle).
- Share lifecycle funnels (Explore-mapped + read): SEND start `EngineSendController.sendTo`,
  terminal funnel `stop()`. RECEIVE armed via `startForeground` (2 sites), single disarm
  funnel `fullTeardown()` (called from EXPIRE/OFF/teardownToArmed-when-OFF/onDestroy line 708).
- Trigger/discovery is BLE (sender writes creds via GATT); Wi-Fi only needed for the
  actual link — so per-active-transfer would suffice, BUT user chose to hold Wi-Fi for the
  whole armed-receive window too (2026-06-25).

## DESIGN DECISIONS (user, 2026-06-25)
- Hold Wi-Fi for the **whole receive-armed window** (not just active transfer).
- **Re-sign** bridge-share-direct with the standard debug key (vs re-keying the helper).

## ON-DEVICE TEST SCRIPT (owed to user — UI-driven, the only real verification)
Precondition: phone already has `radio-helper` installed + WRITE_SECURE_SETTINGS granted
(+ self-ADB pairing on ColorOS) — the one-time per-phone helper setup; NOT redone per boot.
1. Uninstall any OLD `com.bridge.share.directapp*` (signature changed — in-place update blocked).
2. Install the new `bridge-share-direct-debug.apk`.
3. Turn Wi-Fi OFF manually.
4. Open the app, enable receive (Always-on). EXPECT: Wi-Fi turns ON silently within ~1s.
   `adb logcat -s BridgeRadioCoord` → `acquire(receive) … prepareForShare done`.
5. From a second device, send a file → EXPECT receive completes over Wi-Fi.
6. Set receive OFF (or let TIMED expire). EXPECT: Wi-Fi restored to OFF (its pre-share state).
   logcat → `release(receive) … transferFinished; Wi-Fi restored`.
7. Send test: Wi-Fi OFF, open send sheet, pick a peer, send. EXPECT Wi-Fi ON for the send,
   restored to OFF when the sheet closes.
8. Helper-absent fallback: uninstall the helper, repeat — app must NOT crash; share just
   fails if Wi-Fi is off (graceful no-op, `helper not connected` in logcat).

## STATUS
Compile-only target as of 2026-06-25. End-to-end (Wi-Fi actually flips via helper +
restores, real transfer) UNVERIFIED — needs the test script above on a helper-paired phone.
