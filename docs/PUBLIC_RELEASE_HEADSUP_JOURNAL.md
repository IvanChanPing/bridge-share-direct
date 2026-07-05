# Bridge Share Direct — Public GitHub Release + No-Overlay Heads-Up + Helper Overlay-Grant

## CURRENT STATE / NEXT STEP
- **Goal (user, 2026-07-05):** (1) FEATURES FIRST — add a Super Drop-style heads-up
  consent notification for the no-overlay receive path + a one-tap overlay-grant button in
  the radio-helper; (2) THEN push `bridge-share-direct` PUBLIC to GitHub (IvanChanPing),
  with CI that compiles BOTH the app AND the radio-helper, publishes BOTH APKs to Releases,
  and a README with "Download" buttons for BOTH.
- **Order (user-confirmed):** Features first, then push.
- **HEADS-UP DROPPED (user, 2026-07-05):** user accepts the bottom-sheet as the no-overlay
  fallback and does NOT want the heads-up notification. Phase 1 CANCELLED. (The bg-activity-start
  gap finding still stands as a known caveat but the user is fine with it.)
- **Also filed (separate project):** Super Drop send-sheet "grow-open" TODO →
  `bada-fork/docs/SUPERDROP_TODO.md` item #1. Not being built now.
- **ACTIVE FEATURE = Phase 2: radio-helper ONE-TAP OVERLAY GRANT.** User picked (A) 2026-07-05:
  resume the push, build this first, then repo+CI+README.
- **DONE 2026-07-05 (compile-green, both BUILD SUCCESSFUL):** Phase 2 overlay grant implemented.
  Helper (bada-fork): `AdbWifiManager.grantOverlay` (appops set+get verify) → `RadioToggler.grantOverlay`
  → `RadioService.MSG_GRANT_OVERLAY=7`. Client: `RadioHelperClient.grantOverlay()` (bridge copy AND
  bada template synced) + `OverlayGrantHelper.kt` (Java bridge) + MainActivity "Enable overlay" button
  now one-tap w/ Settings fallback. Built: radio-helper-debug.apk (13.2MB) + bridge-share-direct-direct-debug.apk.
  CHANGELOGs updated both repos. Runtime grant still device-UNVERIFIED (needs helper's one-time pairing).
- **NEXT EXACT STEP: THE PUSH (Phase 3).** (1) create GitHub repo IvanChanPing/bridge-share-direct (PUBLIC)
  via gh; (2) add `radio-helper/` as an independent standalone Gradle project in the bridge repo (copy the
  module, convert its build.gradle.kts version-catalog refs → inline, add jitpack + Shizuku/Conscrypt/BC deps,
  own settings.gradle + gradle 8.7 wrapper); (3) add a gradle 8.7 wrapper to the app too; (4) ONE shared
  committed debug keystore both sign with; (5) `.github/workflows/release-apk.yml` — on `v*` tag + manual,
  JDK17 + Android SDK, build BOTH APKs, publish BOTH to a Release (stable names); (6) README with TWO Download
  buttons (app + helper) → releases/latest/download/…; (7) push via git-push-proxy skill.

## PRE-BUILD RISK PASS — Phase 2 overlay grant (2026-07-05)
- **VERIFIED mechanism:** `AdbWifiManager.runShell(ctx,cmd)` opens `shell:<cmd>` over libadb
  autoConnect (shell UID 2000). Already runs `svc wifi enable` + `pm grant … WRITE_SECURE_SETTINGS`.
  So `appops set <pkg> SYSTEM_ALERT_WINDOW allow` runs the same way. `appops set … allow` = the
  documented grant for draw-over-apps (verified via docs this session).
- **VERIFY-DON'T-TRUST (reuse selfGrantWriteSecureSettings pattern):** runShell returns non-null
  even when the command errors → after `appops set`, run `appops get <pkg> SYSTEM_ALERT_WINDOW`
  and check output for "allow" (lenient; record raw output in a lastGrantOutput-style field).
  UNVERIFIED: exact `appops get` output format across OEMs → make the parse lenient + observable.
- **PRECONDITION:** self-ADB paired + wireless debugging on (one-time helper setup). If not,
  runShell→null→grant returns false → client surfaces "do the helper's one-time setup". Self-ADB
  itself is DEVICE-UNVERIFIED on ColorOS (user accepts).
- **CALLER PKG:** client sends its own packageName in the Message data Bundle (reliable; getCallingUid
  in a Messenger handler is unreliable). Trust = BIND_RADIO signature perm (same-key apps only).
- **ENTRY POINTS (change together):** helper AdbWifiManager + RadioToggler + RadioService; client
  RadioHelperClient (bridge copy AND bada template); Bridge Share MainActivity button. MSG_GRANT_OVERLAY=7
  must match everywhere.
- **THREADING:** RadioService handler is off-main (handlerThread) → blocking runShell OK. Client async.
- **OBSERVABILITY:** record raw appops output + log; Bridge Share button re-checks its own
  `Settings.canDrawOverlays` after the reply and reports success/fail.
- **VERIFY REACHABILITY:** can compile both here; real grant needs the paired device (user) → hand a
  test script.

## VERIFIED FACTS (this session, 2026-07-05)
- **Repo = `bridge-share-direct`** (user chose this over shareit-bridge). Public. Fork of
  shareit-bridge (the ORIGINAL pre-iPhone Bridge Share); NO iOS/OnePlus code. pkg
  `com.bridge.share.directapp`, app label "Bridge Share Direct". Single-module, app at repo
  ROOT (build.gradle at root, src/ at root). No gradle wrapper (builds via /opt/gradle-8.7).
  No GitHub remote yet. Has committed APK `bridge-share-direct-debug.apk`.
- **Receive UI fallback chain (code-traced):** `ReceiveService` (FGS) → `ReceiveUi.show()`
  (ReceiveService.java:392) → if `Settings.canDrawOverlays` → `OverlayReceiver` card; ELSE
  bare `ctx.startActivity(ReceiveBottomSheetActivity)` (ReceiveUi.java:53-54). MainActivity.java
  "Preview receive (sheet/overlay)" btn → `ReceiveUi.preview()` (:119); "OVERLAY ON/OFF" btn →
  deep-link `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` (:124). **NO fullScreenIntent, NO
  notification-based accept prompt anywhere** (only the IMPORTANCE_LOW "Receiving" FGS status notif).
- **KEY FINDING — the no-overlay fallback likely does NOT surface from background:** the sheet
  is launched by a background Activity-start FROM A FOREGROUND SERVICE, which Android restricts
  (API 29+, tightened 31+; docs: developer.android.com/guide/components/activities/background-starts).
  So "Always on, app backgrounded, overlay off" → sheet probably never appears = effectively NO
  working fallback. (Code+docs; NOT device-observed — definitive check = arm receive, background
  app, send from 2nd phone.) ⇒ The heads-up notification is the CORRECT FIX: posting a notification
  from bg is always allowed; tapping Accept on a notification is a documented BAL exemption → can
  launch the importance-100 foreground-hop for Wi-Fi-Direct connect(). A14+ USE_FULL_SCREEN_INTENT
  is calling/alarm-gated, but that only affects screen-off full-screen launch; the IMPORTANCE_HIGH
  heads-up banner + buttons still show.
- **REUSE BASE = `headsup-demo`** (`/root/agent-work/projects/headsup-demo`, pkg `dev.headsup`):
  DEVICE-PROVEN (real Pixel 8 + emulator) heads-up consent notification built FROM Super Drop's
  ConsentNotification. Files: `HeadsUpNotifier.kt`, `ConsentActionReceiver.kt`,
  `notification_headsup.xml`, `notif_btn_accept_bg.xml`/`notif_btn_decline_bg.xml`, `Placeholders.kt`.
  Verified gotchas: IMPORTANCE_HIGH channel peek; fresh notif id per post (same id doesn't re-peek);
  DecoratedCustomViewStyle keeps native frame (light bg) / fully-custom RemoteViews for a black bg;
  RemoteViews only (no vector; thumbnail via Canvas bitmap). Bridge itself has NONE of this yet.
- **Super Drop button look (the target):** Accept = filled blue `#0A84FF` pill (22dp radius);
  Decline = neutral `#EDEDF2` pill w/ red label. (BRIDGE-style variant = dark `#202024` card,
  blue accept text `#4FA3FF`.)
- **radio-helper** (`bada-fork/radio-helper`, pkg `dev.superdrop.radiohelper`, targetSdk 28):
  standalone module, NO project() deps, but uses bada-fork version-catalog + jitpack + Shizuku/
  Conscrypt/BouncyCastle. Has the self-ADB stack (`adbwifi/AdbWifiManager`,`AdbMdns`,`AdbWifiRadio`
  +`AdbWifiBootReceiver`) that pairs once w/ Wireless Debugging and runs UID-2000 shell — TODAY runs
  `svc wifi enable`; adding `appops set <pkg> SYSTEM_ALERT_WINDOW allow` = one more command on the
  same channel → one-tap overlay grant (persists across reboot → no-per-boot OK). Self-ADB is
  compile-only / DEVICE-UNVERIFIED on the user's ColorOS. User accepts it (single command + one-time
  setup fine). See [[reference_auto_grant_overlay_appop_feasibility_2026_07_05]].

## PLAN
### Phase 1 — Heads-up consent notification (no-overlay path)
- Port headsup-demo's notification (HeadsUpNotifier + layout + 2 pill drawables + Placeholders +
  action receiver) into `com.bridge.share.ui` as the no-overlay accept prompt. Adapt: Accept →
  `TransferAcceptActivity` hop; Decline → controller.decline(); wire to ReceiveController live states.
- Decision to settle: does the heads-up REPLACE the bottom-sheet on the no-overlay path, or is the
  sheet kept as the tap-to-open detail surface (Super Drop keeps both — notification + trampoline sheet)?
- Build (gradle 8.7), user device-tests (arm receive, background app, send from 2nd phone).
### Phase 2 — radio-helper one-tap overlay grant
- Add an `appops set <targetPkg> SYSTEM_ALERT_WINDOW allow` command over the existing self-ADB
  channel + a helper UI button + (optional) an IPC verb so Bridge Share can request it.
### Phase 3 — GitHub push + CI + README
- Keep app build as-is at root; add `radio-helper/` as an independent standalone Gradle project
  (convert its build.gradle.kts version-catalog refs → inline; add jitpack). Add gradle 8.7 wrapper(s).
- ONE shared committed debug keystore signs BOTH (BIND_RADIO signature-perm match).
- `.github/workflows/release-apk.yml`: on `v*` tag + manual, JDK17 + Android SDK, build BOTH APKs,
  publish BOTH to a Release (stable asset names).
- README: two Download buttons → `releases/latest/download/{bridge-share-direct-debug.apk,radio-helper-debug.apk}`.

## OPEN DECISIONS
- Phase 1: heads-up replaces sheet, or heads-up + keep sheet as detail trampoline? (lean: keep both like Super Drop)
- Phase 3 tension: building the universal helper from source here forks it (user said "compile both" → accept).

## LOG
- 2026-07-05: Scoped. Confirmed repo (bridge-share-direct/public), order (features-first). Traced
  receive fallback (overlay card / bare-startActivity sheet; no heads-up). Found background-start gap
  (fix = heads-up w/ notification+fullScreenIntent). Found reuse base headsup-demo (device-proven).
  Confirmed radio-helper self-ADB can do the one-tap overlay grant. No code written yet.
