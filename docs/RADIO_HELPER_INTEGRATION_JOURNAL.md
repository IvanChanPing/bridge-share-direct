# Bridge Share Direct ↔ Universal Radio-Helper Integration — Task Journal

## CURRENT STATE / NEXT STEP
- **Goal:** wire `bridge-share-direct` (the de-iPhone'd standalone SHAREit Wi-Fi-Direct
  fork) into the ONE universal `radio-helper` APK so it silently enables Wi-Fi for
  shares and restores it — the same helper used by `oconnect-bridge`, per the
  "helper works with multiple apps" policy.
- **DONE (code, 2026-06-25):** all wiring written + signing key fixed. Building.
- **IN PROGRESS:** `gradle assembleDirectDebug` compile verification.
- **NEXT EXACT STEP:** confirm BUILD SUCCESSFUL → copy APK to repo root → commit +
  CHANGELOG → hand user the on-device test script (below). On-device behaviour is
  UNVERIFIED (no device/helper-paired phone in this env).

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
