# Changelog — bridge-share-direct ("Bridge Share Direct")

> **Fork.** This project is a fork of `shareit-bridge` (the original standalone
> "Bridge Share" app), created 2026-06-22 from commit `9e3d1d5`, to preserve and
> continue the **pre-iPhone-mimic standalone Share app**. The iPhone-mimic /
> OnePlus-OShare / Common-Share-DCP work lives in the *separate* `oconnect-bridge`
> fork and is intentionally **NOT** present here — this fork is purely the SHAREit
> Wi-Fi-Direct port. Only the app identity was changed from the source:
> - `app_name`: `Bridge Share` → **`Bridge Share Direct`** (`src/main/res/values/strings.xml`)
> - `applicationId`: `com.bridge.share` → **`com.bridge.share.directapp`** (`build.gradle`)
>   (namespace `com.bridge.share` kept, so no source files moved; installs
>   side-by-side with the original Bridge Share and the oconnect-bridge build).
> - `rootProject.name`: `shareit-bridge` → `bridge-share-direct` (`settings.gradle`)
>
> The product flavors are unchanged: `direct` (Wi-Fi Direct, primary), `aware`,
> `hotspot` — so the installable packages are `com.bridge.share.directapp.direct`
> etc. Everything below this block is the inherited history from `shareit-bridge`.

---

A standalone Android↔Android peer-to-peer file-share app. The peer-to-peer
connection (the "bridge") is a faithful, bite-for-bite port of SHAREit Lite
3.17.58's Wi-Fi-Direct connection method (decompiled + deobfuscated locally),
and the file-transfer layer is a faithful port of SHAREit's control-channel +
embedded HTTP-server transfer. The ONLY intentional deviations from SHAREit:
the bridge is triggered out-of-band via **NFC** and **BLE** (creds exchange,
lifted from oshare-port), instead of SHAREit's own BLE-radar + Wi-Fi-scan
discovery. No LocalSend, no shared-Wi-Fi-LAN, no invented protocol.

Source of truth for the SHAREit method:
`/root/agent-work/projects/shareit-study/jadx-deobf/sources` (deobfuscated) and
`jadx-out/sources` (readable core). See memory
`reference_shareit_deobfuscation_method_2026_05_29`.

## [Unreleased]

### 2026-06-03 — Sender "Sent" fired too early (before the receiver finished) — completion ACK
Cosmetic mismatch (transfer was fine): the sender showed "Sent" the instant ShareHttpServer flushed the last
byte to the socket (serveDownload all-items-served), which beats the receiver by the socket-buffer drain +
disk-write time. Fix: the receiver (DownloadClient) now pings the sender's NEW `/complete` (alias `/done`)
endpoint AFTER writing all files to disk; the sender fires its completion (onAllServed -> "Sent" + group
teardown) on that ack instead of on last-byte-served, so "Sent" lands in step with "received". If the ack is
lost (e.g. old receiver), a COMPLETE_FALLBACK_MS=2500ms daemon timer fires completion anyway so the sender
never hangs on "Sending…". The /complete ping reuses the same bound network as the pull. Built OK; not yet
device-verified.

### 2026-06-03 — VPN: last-resort bind to the Wi-Fi network when no Direct network is bindable
"Try harder" attempt to make the pull survive a full-tunnel VPN. Since the p2p interface is never exposed as
a ConnectivityManager Network on these phones, P2pNetworkFinder now: (1) matches a non-VPN network that
carries a ROUTE to 192.168.49.0/24 (added previously), and (2) NEW — if a VPN is active AND nothing matched,
binds the pull to the first non-VPN Wi-Fi network as a last resort (DownloadClient.openHttp ->
network.openConnection escapes the tunnel). On OEMs running the p2p group in concurrent mode the group owner
is reachable via Wi-Fi; if not, it's no worse than the captured default route. Gated to vpnActive ONLY, so the
working non-VPN default-route path is untouched. Logged as WIFI-FALLBACK. Built OK; needs a real VPN-on test
to confirm whether the Wi-Fi network actually reaches 192.168.49.1 on these phones.

### 2026-06-03 — P2P/VPN diagnostics (the VPN-bypass never actually binds; logs lacked detail)
Investigating "wasn't doing the handshake with VPN on". Log finding: the LAST captured transfer (CPH2583
16:51:47) actually COMPLETED end-to-end (joined 192.168.49.1:2999 -> manifest -> 54%->100% -> done ->
published to gallery -> "transfer complete"; the "channel disconnected" right after is the normal teardown).
BUT P2pNetworkFinder logs "no P2P network found among N networks (will use default route)" on EVERY attempt
-> it never binds the pull to the Direct interface (the OEM doesn't expose p2p-* as a ConnectivityManager
Network), so there is NO real VPN bypass; it only works when the path to 192.168.49.1 isn't captured. The old
log only recorded a network COUNT, so it couldn't tell whether a VPN was even active.
Fix (diagnostics + slightly better match): findDirectNetwork now logs, per network, transports
(vpn/wifi/cell), iface, addresses and whether it carries a route to 192.168.49.0/24, plus vpnActive; it now
also matches on a DIRECT-subnet ROUTE (not just an address) and explicitly NEVER binds to a VPN network.
Next "VPN on" capture will show the exact routing. No behavioural change when nothing matches (default route,
same as before). Built OK; not device-verified.

### 2026-06-03 — Receive Accept: stop the foreground-hop launch from janking the collapse animation
On Accept the overlay card ran BOTH synchronously on the tap frame: onAccept() (launches
TransferAcceptActivity — a foreground activity = window/focus/importance change on the main thread) AND
beginLiveProgress() (the collapse-to-pill animation). They contended on the main thread, so the collapse
janked — exactly the "both changed at the same time and conflicted" lag the user reported. Fix in
OverlayCard.ackContent Accept handler: collapse IMMEDIATELY (instant feedback, smooth while the thread is
free), then launch the hop via postDelayed(ACCEPT_HOP_DELAY_MS=320ms), after the ~300ms collapse settles.
The connect inside the hop easily absorbs the 320ms. Built OK; not yet device-verified.

### 2026-06-03 — NFC edge glow: slower pulse + per-position shimmer (different parts at different speeds)
User wanted the ring's dim->bright pulse slowed and made less uniform (different parts moving at different
speeds). Two changes:
- SLOWER: GlowView envelope durations x SLOWDOWN 1.9 (500/250/400/750 -> 950/475/760/1425, total ~3610ms).
  NfcTapFx detach 2400->3800ms, dim duration 2200->3600ms (covers the full slowed glow), dim 0.34 unchanged.
- SHIMMER: barglow.agsl new `uniform float shimmerTime` (GlowView feeds elapsed seconds via applyAt ->
  pushAnimatedUniforms, seeded 0). main() computes a per-position brightness factor from the fragment's
  angle around the screen centre = 0.72 + three sine harmonics (spatial freq 6/11/3, temporal speed
  1.10/-0.65/0.40), clamped 0.40..1.10, multiplied into the edge line + glow ONLY (not the camera gradient).
  Result: different segments of the ring brighten/dim at different rates instead of one uniform global pulse.
  Tunables: SLOWDOWN in GlowView; the harmonic amplitudes/freqs/speeds + 0.72 baseline + 0.40/1.10 clamp in
  barglow.agsl. Built OK; not yet device-verified.

### 2026-06-03 — NFC edge ring: match the device's REAL screen corner radius (was guessed)
User screenshot (1080x2376) showed the ring corners rounder than the phone's actual screen corners
(CORNER_RADIUS 0.085 -> 202px corner, too round). Replaced the guessed corner constant with the device's
real radius: barglow.agsl CORNER_RADIUS const -> `uniform float cornerRadius` (uv-height units), used in
edgeDist as `max(cornerRadius - EDGE_MARGIN, 0.0)` so the inset ring stays CONCENTRIC with the screen
corner. GlowView.setCornerRadiusPx(px) converts px -> px/viewHeight and re-applies on size changes (seeded
default 0.05). NfcTapFx.roundedCornerRadiusPx() reads WindowInsets.getRoundedCorner(POSITION_TOP_LEFT)
.getRadius() (API31+) and feeds it through TapFxOverlay.setCornerRadiusPx; falls back to the seeded default
when no rounded corner is reported (older API / window not laid out yet). Now the ring corners match the
actual screen corners on any device. Built OK; not yet device-verified.

### 2026-06-03 — NFC tap fx tuning #2: dim darker/quicker/longer, sound rings out, edge ring hugs screen
- DIM: maxAlpha 0.18 -> 0.34 (darker); playDim envelope fade-in 25%->8% (quicker), fade-out start 60%->82%
  (held dark longer); duration 1.9s -> 2.2s (NfcTapFx playDim(2200, 0.34)). Detach still 2400ms.
- SOUND: tap_start.ogg re-built to ring out + fade away instead of the ~0.6s hard cut. Spliced pulse 1
  (0..0.60s, full volume) onto the original's OWN natural reverb tail (0.90..1.40s) via acrossfade d=0.12
  (tri/tri) -> full-volume ding (peak ~0.43s preserved) then smooth decay to silence, total ~0.96s. (First
  try with aecho dropped the level + died at 0.75s — rejected; the splice keeps full volume.)
- EDGE RING ALIGNMENT (barglow.agsl tunables): EDGE_MARGIN -0.012 -> +0.005 (bright line now just INSIDE the
  real screen edge instead of off-screen, so it hugs the edge); TOP_INSET 0.020 -> 0.004 (top hugs the true
  top); CORNER_RADIUS 0.055 -> 0.085 (rounder, follows the phone's screen corners). All still tunable consts.
Built OK; not yet device-verified.

### 2026-06-03 — NFC tap effect: trim sound to one ding + slight screen dim + camera-anchored pieces
Follow-up to the woods tap-effect port, per user feedback:
- SOUND: tap_start.ogg was a 3-pulse chime (main rising "ding" 0.00–0.58s + two extra blips 0.60–0.85s).
  Re-encoded to keep ONLY the first ding at its full natural length (peak/sustain preserved; tiny 30ms
  anti-click fade ending ~0.605s), cutting the two blips. Full original kept at audio-src/
  tap_start_full_original.ogg (moved OUT of res/raw — a .ogg.bak name breaks aapt).
- SCREEN DIM: the ported woods effect had no dim (the old hand-drawn one did). Added a black scrim BEHIND
  the glow+ripple in TapFxOverlay (new dimView + playDim(durationMs,maxAlpha): fade in 25%, hold, fade out
  last 40%). NfcTapFx calls playDim(1900, 0.18) — "a little bit darker" so the glow/ripple pop.
- POSITIONING: the two camera-origin pieces now anchor to the REAL DisplayCutout instead of a hardcoded
  (0.5, 0.027). barglow.agsl: CAMERA_X/Y/SIZE consts -> cameraCenter/cameraSize UNIFORMS (seeded to the
  centred-top default, used in main()'s camUv). GlowView.setCameraAnchor(fx,fy) sets the gradient blob;
  NfcTapFx.cameraAnchor() reads decor.getRootWindowInsets().getDisplayCutout() (API28+) and feeds both the
  glow gradient (setCameraAnchor) and the ripple (playRippleAtFraction). Falls back to (0.5, 0.027) when no
  cutout is reported (e.g. window not laid out yet). Edge-glow border is unchanged (positioned by the shader
  screen-edge SDF). Built OK; not yet device-verified.

### 2026-06-03 — NFC tap effect = the REAL woods effect (ported from oshare-port com.oneplus.oshare.port.fx)
Replaced our hand-drawn Canvas glow/ripple (old NfcTapFx) with the actual OnePlus "lingguang" tap effect
ported verbatim from the oshare woods app — the effect behind its "Play NFC tap effect (glow + ripple +
sound)" button (NfcAnimButton). Ported `com.oneplus.oshare.port.fx` -> `com.bridge.share.ui.fx`:
TapFxOverlay + GlowView (real barglow.agsl RuntimeShader, exact LingguangAnimator envelope) + the
ripple/ package (AOSP RippleShader CIRCLE) + TapFeedbackSound. Assets copied in: assets/barglow.agsl,
assets/chromatic.agsl, assets/glow.png, res/raw/tap_start.ogg + tap_end.ogg + tap_error.ogg.
NfcTapFx is now a thin delegator with the SAME public play(Activity), so both callers (MainActivity
"Test NFC animation" button + NfcLaunchActivity real tap) switch over unchanged. Wired EXACTLY like the
woods button: TapFeedbackSound.playStart + tick, then TapFxOverlay.playGlow() + playRippleAtFraction(0.5,
0.027) — ripple originates at the FRONT-CAMERA punch-hole, not screen-centre.
Three animation pieces (two from the camera): (1) edge glow border wrapping all 4 screen edges
(bar+chromatic glow+sparkle), (2) soft gradient blob pinned on the camera punch-hole, (3) expanding
circle ripple from the camera. Glow envelope 1.9s (500+250 / 400+750), ripple 1.75s; RuntimeShader API33+
(inert below, sound+haptic still fire). Removed the wrongly-staged nfc_help_touch.json (that was the
in-app tutorial Lottie, not this effect). Sound = ONLY tap_start.ogg (stock NfcNci start.ogg, 1.40s, a
3-pulse chime) + EFFECT_TICK haptic. Built OK; not yet device-verified.

### 2026-06-03 — Sender pop-up (same floating card as the receiver, alongside the bottom sheet)
The sender used to get only the bottom sheet; the floating island pop-up was receiver-only. Now, the
moment a send starts (you pick a device), the SAME `OverlayCard` floats up showing "Sending to <device>"
with a progress ring, collapses to the island pill over the camera, and shows "Transfer sent" on
completion — mirroring the receive pop-up, in addition to the bottom sheet. Implementation:
- `OverlayCard` got a `role` ("receive" default, unchanged / "send") + `peerName`. In send mode it skips
  the Accept/Decline ack and starts straight in the progress state; labels are role-aware ("% sent / To X",
  "Transfer sent / 1 file sent.", "Send failed"); complete shows no buttons (the sender owns the files).
  The receiver path is byte-for-byte unchanged (role defaults to "receive").
- `OverlayReceiver.showSend(ctx, peerName)` adds the send card to the same overlay window (accessibility
  island preferred, app-overlay fallback) and returns a `SendHandle` with progress()/complete()/failed()/
  dismiss(). Window logic is duplicated from show() on purpose, to not touch the verified receive path.
- `SendSheetActivity` calls showSend() in beginSend() and forwards onProgress/onComplete/onError +
  onStatus("No response") into the handle; onDestroy dismisses it (the send dies with the sheet).
Appears only where an overlay can be added (island a11y enabled OR draw-over-apps granted); otherwise the
bottom sheet remains the only sender UI. Built OK; NOT yet device-verified.

### 2026-06-03 — Retry the manifest fetch (fixes intermittent "failed to connect")
Both phones' live DiagLog uploads showed the Wi-Fi-Direct connect ALWAYS succeeds (`groupFormed=true
owner=192.168.49.1`); the intermittent failure is one layer up — the first HTTP pull races the P2P route.
CPH2515 (20:57): link up at 12.900, then `DownloadClient ConnectException: Failed to connect to
/192.168.49.1:2999` at 13.091 (+189ms) in `fetchManifest`, and `fetchManifest()` was a single attempt with
no retry → the whole transfer aborted. CPH2583 (21:21), same code path, got the manifest at +286ms and
succeeded. Pure timing race ("works oftentimes") because the client's DHCP/route to the group owner isn't up
the instant the group forms. Fix: `fetchManifestWithRetry()` retries the first connect up to 6× at 500ms
(~2.5s window), retrying only IOExceptions (connect/read) — JSON/parse errors still propagate. The link is
already up; it just needs a moment.

### 2026-06-03 — Fix on-device crash/ANR (DiagLog runaway) + position the pill over the camera
Real-phone data (native tombstone + an ANR trace on CPH2583, A15/OxygenOS15) showed two failures with one
root cause: **DiagLog** (the debug log-uploader) running away. The ANR CPU table had `DiagLog-upload` 56% +
`DiagLog-logcat` 17% (~73% of CPU) and ~70 `OkHttp Connecti…` threads — and there is no OkHttp in the app;
Android's platform `HttpURLConnection` IS `com.android.okhttp`, and `DiagLog.post()` is the only HTTP client
in our code, so those were DiagLog's own POST connections piling up. The earlier native crash was `abort` in
`__init_additional_stacks` (bionic failing to mmap a new thread stack = thread/mapping ceiling) — the thread
explosion. Memory PSI was low, so it was thread/mmap exhaustion, not RAM OOM. The ANR was the consequence:
`TransferAcceptActivity` couldn't service input while the CPU was pegged.
- **DiagLog `DEFAULT_ENABLED = false`** — debug machinery no longer ships ON to real phones.
- **Broke the self-capture feedback loop**: `d()` no longer `Log.i`s the line it enqueues (the reader tails
  our own `*:I` logcat, so it was re-reading and re-enqueueing every line), and the capture command now
  passes `DiagLog:S` to silence our own tag so the reader can't re-feed DiagLog's own output.
- **Exponential backoff** (cap 60s) in the uploader: `post()` returns success; on failure the loop backs off
  instead of opening a fresh `HttpURLConnection` every 3s to a dead endpoint and piling up pool threads.
- **Pill position**: the collapsed island pill rested at `PILL_UP = -22dp` (net ~22dp from the top = the
  app's title-bar height — what the screenshot showed). New `pillRestPx()` computes the resting Y from the
  real `DisplayCutout` (camera punch-hole) so the pill centres over THIS device's camera, with a fallback
  and a clamp so it never rises above the overlay window's top edge. Replaces the reverted blind `-40`.

### 2026-06-02 — Island (retry): match the verified accessibility-overlay pattern + add diagnostics
The first island attempt (reverted) never appeared/worked on-device and I had been shipping it blind.
Researched the proper method from a verified working example (github.com/thbecker/android-accessibility-overlay)
and DynamicSpot's approach, then rebuilt to match it and made it observable:
- Config now includes `android:accessibilityFlags="flagDefault"` (was missing) alongside feedbackGeneric /
  typeWindowStateChanged / notificationTimeout=0.
- `IslandA11yService` obtains its WindowManager from the SERVICE context and logs (DiagLog) on
  onServiceConnected / addOverlay success / addOverlay failure / onUnbind — so the collector log shows
  whether the service actually connected (got past Restricted Settings) and whether the overlay added.
- `OverlayReceiver` a11y path uses minimal proven flags (FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL |
  FLAG_HARDWARE_ACCELERATED), TYPE_ACCESSIBILITY_OVERLAY, gravity TOP, and logs the chosen path; falls
  back to the app overlay when the service is off.
- `MainActivity` shows on-screen steps for the sideloaded unlock (Allow restricted settings → enable the
  service) and an Island ON/OFF status button.
Pill position left at the original -22 this round to isolate the a11y-overlay fix from the move-over-camera.
Test path: enable the service, then "Preview receive (sheet/overlay)" shows the island via the a11y overlay.
NEEDS on-device verify WITH the diag log so we stop guessing the failure mode.

### 2026-06-02 — NFC: present the "sender" HCE card ONLY while the send sheet is open
Directional tap fix. Both phones ran an always-on HCE card: `CredsHceService` is registered
`category="other"`, so it answered the NDEF-Type-4 `SELECT` whenever the component was enabled —
independent of `armTrigger()`/`setCreds()` (verified in `processCommandApdu`, which returns OK to the
NDEF AID regardless of armed state). With both phones presenting a card, which one became the NFC
reader vs card on a tap was left to hardware arbitration. On the test pair it almost always landed
CPH2515=card / CPH2583=reader, so when CPH2583 tried to send it lost the card role and the tap never
started a transfer — verified from the diag logs: CPH2583 won the card role only 2 of ~25 taps, and
on exactly those 2 its send worked.
Fix (scoped to the SENDER only; receiver path untouched): `CredsHceService` is now
`android:enabled="false"` by default, and `EngineSendController` enables the component in `startScan`
(send sheet opened) and disables it in `stop` (send sheet closed) via `setComponentEnabledSetting`.
So a device presents the sender card ONLY while its send sheet is open; otherwise it presents no card
and is purely the reader/receiver, making the reader/card role deterministic (only the sender has a
card to be read). Files: `AndroidManifest.xml`, `ui/EngineSendController.java`. NEEDS on-device
verification (NFC routing-table update latency on component enable; both directions).

### 2026-06-01 — Sender status + retry: release the "already sending" latch on failure
The sender should SEE every development. Connecting / Sending / Sent / Failed already render on the
device icon; the gap was that the `sending` latch in `SendSheetActivity` never reset, so after a
`Failed` (e.g. host failed: 2/BUSY) or a `No response` timeout the user could not retry — the re-tap
was swallowed by `send ignored; already sending`. Now `onError` and the `No response` status reset
`sending=false`, relabel the icon ("Failed – tap to retry" / "No response – tap to retry") and the
sheet header ("Tap to retry"); the existing per-icon click listener re-runs `beginSend` →
`controller.sendTo` (which already tears down the prior attempt first), so the retry is clean.
NOT YET tested on-device. NOTE: "Declined" is still NOT distinguished from "No response" — the
receiver only logs DECLINE locally; surfacing a true "Declined" to the sender needs a
receiver→sender back-signal (BLE/GATT), which is a separate follow-up.

### 2026-06-01 — Host createGroup: recover from reason=2/BUSY (stale P2P group)
Diagnosed from the 06-01 device logs (CPH2583/A15 sender): the first `createGroup` right after a
fresh process/share-sheet start failed `reason=2/BUSY` (`SendSheet: ERROR host failed: 2/BUSY`),
and the UI "already sending" latch then swallowed the retry, dead-ending the send. Root cause:
`WiDiNetworkManager.start()` called `createGroup` with no pre-clear, so a lingering P2P group held
by the framework from a prior session/process makes the framework reject the request as BUSY (the
join path already pre-clears via `clearStaleGroup`; the host path never did).
Fix (reactive, zero added delay on the clean path — per the user's "no delay if nothing to tear
down"): `createGroup` still fires immediately as before; ONLY on a `BUSY` failure does it now
`removeGroup` then retry `createGroup` once (500ms settle), guarded to at most one retry per send
(`clearedForBusy`). A clean host never calls `removeGroup`, so it pays nothing. Builds
`:assembleDirectDebug` clean. NOT YET tested on-device. Still open: the UI `already sending` latch
should also release when the createGroup dispatch fails (secondary; the auto-retry largely covers
it). Other known issues unchanged (mid-transfer socket drop; overlay window-resize jank; A15 NFC
routing set aside per user).

### 2026-05-31 — NFC reliability: re-apply the sticky-tapped fix on the transparent base
On the restored transparent NfcLaunchActivity base (a287b46), re-applied the verified
sticky-tapped fix that was lost in the revert: an NFC-initiated receive sets `nfcTappedUntil`
(WOKEN_IDLE_MS window), and `armCheapListeners` advertises tapped if `nfcTapped || within
window`. This stops a routine START_STICKY/arm() re-arm from clobbering the tapped advertise
~5ms after the tap (verified on-phone: the sender saw `tapped=false` → no auto-send). The fix is
flow-independent, so it applies to the transparent-activity path too. No app takeover (rule kept).

### 2026-05-31 — Restore NFC to the transparent (no-app-takeover) version (a287b46) as the base
Per the user: the NFC that worked WITHOUT pulling the user into the app was the transparent
`NfcLaunchActivity` version; my later "revert NFC to MainActivity" (ffbd555) reintroduced the
forbidden app-takeover and was the wrong move. Restored the four NFC source files
(`AndroidManifest.xml`, `MainActivity`, `NfcLaunchActivity`, `ReceiveService`) to their `a287b46`
state — the version before that revert — to use as the base for fixing the remaining issues
(NFC not 100% reliable + background-kill). This UNDOES the MainActivity NFC handling (ffbd555)
and the sticky-tapped tweak (df95f6c); all other fixes (crash logger, reason=0 join fix,
pre-start revert, persistent advertise) are in a287b46 and preserved.
Verified against the official NFC docs: an AAR does NOT bypass NDEF intent-filter matching — it
starts the in-package activity that matches the filter, only falling back to the launcher if
MULTIPLE or NO activities match. So the transparent single-NDEF-filter activity is the correct
design; removing the AAR (a guess) was rejected.

### 2026-05-31 — Fix NFC auto-send: make the "tapped" flag sticky (routine re-arm was clobbering it)
On-phone logs showed why NFC didn't auto-start: the receiver's NFC-initiated receive set the
presence "tapped" flag true, then a ROUTINE re-arm (START_STICKY/arm(), tapped=false) reset it
~5ms later (`re-advertised with nfcTapped=false`), so the sender saw the peer as `tapped=false`
and never auto-sent.
- `ReceiveService`: added `nfcTappedUntil` — on an NFC tap we set a sticky window (WOKEN_IDLE_MS);
  `armCheapListeners` now advertises tapped if `nfcTapped || within window`, so a routine re-arm
  can no longer clobber the tapped advertise before the sender catches it.
- Also CONFIRMED (logs) the background drop-off = the receiver FGS is silently OEM-killed after a
  while (`msSinceProcessStart` ~118ms on the next NFC receive = fresh spawn); no crash log (OEM
  kills aren't crashes). Tracked as the optional background-survival item.

### 2026-05-30 — Revert NFC launch to the working (MainActivity-handled) version
NFC stopped launching the app after `NfcLaunchActivity` was introduced (commit e515d51) — logs
showed `NfcLaunchActivity` NEVER launched on a tap. Likely cause: the Android Application Record
steers the NFC dispatch to the app's LAUNCHER activity (MainActivity), which had lost its NDEF
filter when handling moved to the non-launcher NfcLaunchActivity → nothing matched. Per the user,
reverted just the NFC launch to the version that worked (e378c1d..bbc849d):
- Deleted `NfcLaunchActivity`; restored `MainActivity` `handleNfcLaunch` + `ensureBluetoothOn` +
  `onNewIntent`, the `NDEF_DISCOVERED` intent-filter, and `launchMode="singleTop"`.
- Tradeoff accepted: a tap brings up the app (the "forced into the app" behaviour) — working
  beats polished. Sender auto-pick / tapped-flag / LOW_LATENCY are unchanged.
Also added a TODO: the overlay jitter is likely the foreground-hop-finishing vs overlay-animation
timing competition (user hypothesis), and the auto-updater spec.

### 2026-05-30 — Crash logger + reason=0 join-race fix + revert pre-start + NFC launch logging
Driven by a full two-phone log comparison (CPH2515 Android 14 = 12 sends / 8 NFC auto-sends;
CPH2583 Android 15 = 0 sends — NFC one-directional; and receiver join failing reason=0 ×4 then
succeeding ~18s later).
- **Crash logger** (`DiagLog.installCrashHandler` + `uploadPendingCrashes`, wired in `BridgeApp`):
  an UncaughtExceptionHandler writes the stack trace to `diag/crash-*.txt` synchronously (a
  crashing process can't upload), and the next launch uploads + deletes it. (Previously crashes
  the user saw never reached the collector — fixed.)
- **reason=0 join race FIX** (`TransferAcceptActivity`): the invisible foreground hop used to
  `finish()` immediately after dispatching `connect()`, so the connector's retries (1.5s apart)
  ran at importance 125 → `reason=0` ×4. It now HOLDS importance 100 until the join reports back
  (`dismissNow` on `onJoined`) or 30s, and is `FLAG_NOT_TOUCHABLE|NOT_FOCUSABLE` so it passes all
  input through (doesn't block the screen).
- **Reverted pre-start** to the proven "fresh host per send" path (tear-down-first), and the host
  now tears the Wi-Fi-Direct group down after each completed send (`endGroupAfterSend`) — helps
  the next join hit a fresh group. (Pre-start gave no speedup; the bottleneck is the GATT write.)
- **NFC launch diagnostics**: `MainActivity` logs its launch intent (to catch the AAR launching
  the launcher instead of `NfcLaunchActivity`); `CredsHceService` logs the SELECT-AID the reader
  requests (to see, on the Android-14 phone, whether our HCE is selected and with which AID).

### 2026-05-30 — Revert pre-start; speed up the GATT trigger; overlay stops eating taps
On-phone logs showed the regression: across ALL attempts the send latency is dominated by the
BLE GATT creds-write (`sendTo`→connected ~1–3s, then a consistent **~1.7s MTU exchange**), NOT
group formation — so pre-start gave no speedup, and it churned a Wi-Fi-Direct group on every
sheet-open + produced GATT-connect failures (stale rotated BLE address) → "slower and less
reliable."
- Reverted pre-start: `SendSheetActivity` no longer calls `prewarm`; the host comes up on the
  actual `sendTo` again (the `startHost`/`deliverCreds` split is kept, just not pre-warmed).
- `CredsGattWriter`: request `CONNECTION_PRIORITY_HIGH` on connect (before the MTU request) to
  shrink the connection interval and cut the ~1.7s MTU+write stall.
- `OverlayReceiver`: the overlay window is now `WRAP_CONTENT` height instead of a fixed 320dp
  full-width band, so it only consumes taps on the card itself; taps elsewhere pass through
  (fixes "the overlay preventing certain taps"). Tiny scale-pop overshoot may clip — verify.

### 2026-05-30 — Send timeout → "No response" (sender no longer hangs on "Sending")
User: "no notification to the sender if it was canceled." First half of the declined-feedback
item: `EngineSendController` arms a 45s watchdog when creds are delivered to the picked peer;
if the receiver never starts pulling (declined / walked away / failed to join) the sender shows
"No response" instead of "Sending" forever. Cancelled on first progress / complete / error /
stop. (The explicit decline SIGNAL — receiver→sender BLE back-channel for an immediate
"Declined" — is still a follow-up.)

### 2026-05-30 — Sender pre-starts the host when the share sheet opens (faster send)
The sender now brings the host up (Wi-Fi-Direct group + HTTP server + published creds) the
moment the share sheet opens, before a receiver is picked — so when one is tapped/picked the
creds are already published and we just write them to the peer, cutting the group-formation
wait from the per-send path.
- `SendController.prewarm(uris)` (default no-op); `EngineSendController` refactored so host
  bring-up (`startHost`) is separate from the per-peer creds write (`deliverCreds`): `prewarm`
  starts the host; `onHostPublished` stores the creds and delivers to the active peer if one
  is already chosen; `sendTo` delivers immediately if creds are ready, else waits for publish.
  One host per sheet (no EADDRINUSE re-bind); state reset in `stop()`.
- `SendSheetActivity.onCreate` calls `prewarm(uris)` right after `startScan`.

### 2026-05-30 — Faster NFC: LOW_LATENCY presence advertise while NFC-tapped
`PresenceAdvertiser` now advertises in ADVERTISE_MODE_LOW_LATENCY while the NFC-tapped flag is
set (a brief active window), so the sender's scanner discovers the tapped receiver fast; idle/
armed stays LOW_POWER for battery. Speeds up the NFC tap-to-transfer.

### 2026-05-30 — NFC: don't pull the user into the app; collapse duplicate sender icons
On-phone NFC test feedback: the receiver was forced into the full settings app on tap, and
the sender showed the same receiver twice ("two CPH2583" / "two instances").
- `NfcLaunchActivity` (new, transparent + taskAffinity="" + excludeFromRecents + singleTask +
  noHistory): the NDEF_DISCOVERED intent-filter now targets THIS throwaway activity instead of
  MainActivity. It plays the edge-glow over the user's current screen, requests Bluetooth
  enable if off, fires `ReceiveService.nfcReceive`, and finishes — so the receive Accept prompt
  shows over whatever they were doing, not the settings UI. (NFC handling removed from
  MainActivity.)
- `SendSheetActivity`: dedupe device icons by NAME, not BLE address — a receiver's MAC rotates
  (privacy) so one phone was appearing as several icons. Status callbacks (which arrive by
  address) still route by registering the single icon under every seen address.
- STILL OPEN: make the NFC flow faster (BLE discovery + GATT + Wi-Fi-Direct connect latency);
  clarify the user's cut-off "broadcast receiver" remark.

### 2026-05-30 — Fix laggy/jittery overlay card animation (GPU rendering)
User reported the overlay receive card's animation was laggy and jittered. Root cause: a
window added via `WindowManager.addView` is SOFTWARE-rendered by default (unlike an Activity),
so the card's per-frame `ValueAnimator` + `invalidate()` arc/progress drawing ran on the CPU.
- `OverlayReceiver`: added `FLAG_HARDWARE_ACCELERATED` to the overlay window LayoutParams so
  it renders on the GPU.
- `OverlayCard`: hoisted the per-frame `Path`/`RectF` allocations out of the ring views'
  `onDraw` into reused fields (less GC pressure during the arc tween).

### 2026-05-30 — Revert OS-only: persistent minimal presence advertise (on-phone test result)
On-phone logs (CPH2583 receiver) PROVED the wake path works on ColorOS 12 — `WAKE beacon seen
… fgStartOk=true`, `startForeground OK woken=true`, `presence advertise onStartSuccess` — i.e.
a killed/idle receiver IS revived and background FGS-start IS allowed. BUT the OS-only model
was unreliable: the 60s woken-idle watchdog tore the advertise back down, so between wakes the
receiver advertised nothing and the sender (which can only find an actively-advertising
receiver) couldn't detect it. Per the user ("a little bit of battery is fine, the minimal
possible"):
- ALWAYS_ON is again a PERSISTENT foreground service that advertises presence (LOW_POWER
  duty cycle) + GATT continuously, so a scanning sender can always find it. The OS-held wake
  scan is kept as an additive BACKUP that revives the service if the OEM kills it.
- Removed the OS-only idle teardown + woken-idle watchdog; `arm()`/BootReceiver/onTaskRemoved
  start the persistent service for ALWAYS_ON; `teardownToArmed` keeps ALWAYS_ON/TIMED armed
  (advertising) after a transfer; `armOsOnly` is now only the FGS-start-refused fallback.
- Presence advertise TX power LOW→HIGH (range/reliability — "even in foreground wasn't always
  detected"); advertise MODE stays LOW_POWER for battery.
- NFC-tapped advertise flag is cleared on teardown so a sender doesn't keep auto-picking.

### 2026-05-30 — NFC sender auto-pick (receiver signals "tapped" over BLE presence)
So the SENDER needs no icon tap either: the NFC-launched receiver advertises a "tapped" flag
in its BLE presence service data, and the sender auto-sends to that device.
- `PresenceAdvertiser`: presence service data is now `[flags][alias]` (was bare alias);
  `FLAG_NFC_TAPPED` bit + `setNfcTapped()` re-advertises live. `armCheapListeners(true)` on the
  NFC receive path sets it.
- `PresenceScanner`: parses the flag byte; `Callback.onFound(addr, alias, tapped)`. Dedupe key
  includes tapped so a device that becomes tapped after first being seen is re-reported.
- `SendController.Peer.tapped`; `EngineSendController` passes it through; `SendSheetActivity`
  auto-sends (no icon tap) when a tapped peer appears (manual tap still works via the shared
  `beginSend`). Untested — needs the two phones.

### 2026-05-30 — NFC tap-to-receive (NFC as a launch trigger; transfer still over BLE)
Per the chosen design (memory shareit-bridge-nfc-design): NFC is a LAUNCH/TRIGGER, not the
creds transport. A tap launches the receiver's app and forces the normal BLE receive — even
if receive was Off/expired — with no icon-tap on the receiver and (intended) none on the
sender. The creds + transfer still ride the existing BLE + Wi-Fi-Direct path; the Accept
prompt still gates the transfer.
- `CredsHceService`: the served NDEF now always ends with an **Android Application Record**
  (AAR = `BuildConfig.APPLICATION_ID`) so an app-having phone auto-launches us on tap. Also
  registers + answers the **standard NDEF Type-4 AID `D2760000850101`** (apduservice.xml +
  SELECT handler) so a receiver's STOCK NFC dispatch (not in reader mode) can read the NDEF.
- `EngineSendController.startScan`: arms the HCE launch NDEF while the send sheet is open.
- `MainActivity`: handles the NFC launch intent (NDEF/TAG/TECH_DISCOVERED) in onCreate/
  onNewIntent (launchMode singleTop + an NDEF_DISCOVERED intent-filter for
  https://bridgeshare.app). Plays `NfcTapFx`, requests Bluetooth enable
  (`BluetoothAdapter.ACTION_REQUEST_ENABLE`) if BT is off, then calls `ReceiveService.nfcReceive`.
- `ReceiveService.nfcReceive` / `ACTION_NFC_RECEIVE`: forces an active receive window
  (presence advertise + GATT) regardless of the saved mode WITHOUT changing it; a watchdog
  returns to the persisted resting state (OFF → fully down incl. wake scan; ALWAYS_ON →
  OS-only idle; TIMED → stays).
- KNOWN: with an AAR present a no-app phone is sent to Play for the package (not our install
  URL) — a gap until published. Sender-side auto-pick (no icon tap on the sender) is NOT done
  yet (the sender is the HCE card and gets no data from the tap) — pending. UNTESTED (no NFC
  in this env) — needs the two phones.

### 2026-05-30 — Three-option Quick Settings tile (Off / Always-on / 10-min)
`ReceiveTileService` previously toggled OFF↔ALWAYS_ON only. It now CYCLES through the same
three modes as the settings page on each tap — OFF → ALWAYS_ON → TIMED → OFF — calling
`ReceiveService.arm` (ALWAYS_ON = OS-held wake scan only; TIMED = active 10-min window) or
`stop` (OFF). The tile label reflects the mode ("Off" / "Always on" / "10 min"); QS tiles
expose only ACTIVE/INACTIVE so both on-modes show ACTIVE, distinguished by the label.

### 2026-05-30 — Scope narrowed to Wi-Fi Direct only
Per decision, the app is now Wi-Fi Direct ONLY; the Aware and Hotspot flavors are abandoned
(their code remains but is dead — not maintained/tested). Build/ship/test the `direct`
flavor only (`gradle assembleDirectDebug`).

### 2026-05-30 — VPN compatibility: bind the Direct pull to the P2P Network
The receiver's HTTP pull to the group owner (192.168.49.1) failed under an active VPN, which
captures the app's default-network traffic into the tunnel where the P2P peer is unreachable.
Researched the official Android multinetwork API (developer.android.com Network /
WifiP2pManager): `Network.openConnection()` routes a connection over a specific network,
bypassing the VPN/default route; there is NO TRANSPORT_WIFI_DIRECT and WifiP2pManager exposes
no Network, so the P2P Network must be located via ConnectivityManager.
- `conn/P2pNetworkFinder.findDirectNetwork(ctx)`: enumerates `ConnectivityManager.getAllNetworks()`
  and returns the one on a `p2p*` interface or with a 192.168.49.x link address; null (→ default
  route, no regression) if none. Logs the result via DiagLog.
- `WifiDirectConnection.join` onConnected sets `ep.network = findDirectNetwork(ctx)`, which flows
  through BridgeEngine → ShareChannel.startClient → DownloadClient (already uses
  `network.openConnection`). DiagLog uploads already bind to a validated internet network, and
  the host's inbound listening socket is unaffected by a VPN.
- NEEDS phone verify with a VPN ON: if the p2p interface isn't yet a ConnectivityManager Network
  at onConnected, fall back to registerNetworkCallback / retry-with-delay (tracked in TODO).

### 2026-05-30 — Wake-path diagnostics (does a killed receiver get woken? is FGS-start allowed?)
Targeted logging to answer the two open questions for the OS-only model, uploaded to the
DiagLog collector so they can be judged remotely.
- `BridgeApp.PROCESS_START_ELAPSED`: elapsedRealtime at process creation. A small
  `now - PROCESS_START_ELAPSED` delta logged inside a receiver/service proves the process
  was freshly spawned (i.e. a killed receiver was revived by the OS-held scan).
- `BeaconWakeReceiver`: rewritten with `goAsync()` — logs `WAKE beacon seen
  msSinceProcessStart=…`, then calls `ReceiveService.wake` in try/catch and logs
  `WAKE fgStartOk=… [err=…]` (startForegroundService throws
  ForegroundServiceStartNotAllowedException when disallowed on API 31+), then flushes
  synchronously via the new `DiagLog.flushBlocking()` so the lines reach the collector even
  if the process is torn down right after a refused start.
- `ReceiveService.onCreate` logs the same process-start delta; the active foreground promotion
  is wrapped in try/catch logging `startForeground OK/REFUSED woken=… mode=…` and, if refused,
  drops back to OS-only idle instead of crashing.
- `WakeBeacon.registerWakeScan` logs `startScanResult=` (0 = success) directly via DiagLog.
- `DiagLog.flushBlocking()`: synchronous best-effort POST of the current queue (off-main-thread).

### 2026-05-30 — ALWAYS_ON is now OS-only (no persistent background service)
User: "Let's rely only on the OS and see if it worked.. We could always revert." Drops the
persistent foreground service for the always-on background mode; reachability now rides
ENTIRELY on the system-held BLE wake scan (`startScan(filters, settings, PendingIntent)`),
which the OS holds with no process of ours running.
- `ReceiveService.armOsOnly(ctx)` (static, NO service): registers the OS-held wake scan +
  arms the NFC trigger. `arm(ctx)` dispatches by mode — OFF→stop, ALWAYS_ON→armOsOnly,
  TIMED→start (active). All arm entry points (MainActivity, ReceiveTileService,
  SendSheetActivity.onDestroy, BootReceiver, the BT-on re-arm, onTaskRemoved) now go through
  arm()/armOsOnly so ALWAYS_ON never spins up a background service.
- Revival: `BeaconWakeReceiver` → `ReceiveService.wake(ctx)` (new ACTION_WOKEN) brings the
  service up foreground + presence advertise + GATT so a beaconing sender can find us and
  hand over creds. A 60s woken-idle watchdog drops back to scan-only idle if no transfer
  follows (false/expired wake). After a completed transfer, ALWAYS_ON returns to
  `teardownToOsOnlyIdle()` (foreground stopped, advertise stopped, wake scan stays registered).
- Crash-safety: the in-service OS-only branch now goes foreground momentarily before
  stopping (the startForegroundService→startForeground contract), so a sticky restart or
  stray re-arm can't crash with "did not call startForeground".
- TIMED ("discoverable 10 min") is UNCHANGED — it stays an active foreground window.
- KNOWN LIMITATION (verified against Android docs): `BluetoothAdapter.ACTION_STATE_CHANGED`
  is NOT an implicit-broadcast exemption, so a manifest receiver can't catch BT on/off while
  no service runs. With BT turned off the OS cancels a registered scan; after a BT off→on
  cycle with no live process the wake scan must be re-armed by reopening the app (or it
  re-arms on the next boot / send-sheet close). NFC tap-to-receive is not yet wired, so the
  HCE-clear on idle is not a functional regression. REVERTABLE if on-device wake proves
  unreliable. NEEDS phone verify: does a killed receiver get woken by a sender beacon, and
  is the FGS-start from the wake broadcast allowed on ColorOS 12+?

### 2026-05-30 — Turn off BLE when Bluetooth is off (battery) + wake-lock note
User: "I do not want it always running in the background or holding a wake lock... if it
could detect when Bluetooth was on and off to turn itself off when Bluetooth was off and
back [on]." Verified the wake lock is ALREADY transfer-only (`acquireWakeLock` runs only
in `onCredsWritten`, released on teardown) — nothing holds a wake lock while merely armed.
- `ReceiveService.pauseForBluetoothOff()` (wired to `BtStateReceiver.onBluetoothOff`, which
  was previously a no-op): when BT turns OFF, stop the presence advertise + unregister the
  OS-held wake scan (BLE can't run with the adapter off, so leaving them "armed" is wasted
  battery) and cancel watchdogs. The NFC/HCE trigger stays armed (NFC is unaffected by BT).
  The foreground service stays alive but idle (no radio) so the runtime `BtStateReceiver`
  survives to catch BT coming back ON, which re-arms via `armCheapListeners`. An in-flight
  Wi-Fi-Direct transfer is left running (it rides Wi-Fi, not BT).
- Notification now shows "Paused — Bluetooth is off" while paused; reset on re-arm.

### 2026-05-30 — Wake-scan battery tuning (FIRST_MATCH) + battery research
Grounded in Android docs (developer.android.com BLE find-devices, ScanSettings,
doze-standby; saved to memory `shareit-bridge-battery-research`).
- `trigger/WakeBeacon.registerWakeScan`: the always-on system-held scan now uses
  `CALLBACK_TYPE_FIRST_MATCH` + `setReportDelay(0)` (API 23+) on top of
  `SCAN_MODE_LOW_POWER` — we only need the FIRST sighting of a sender beacon to wake;
  FIRST_MATCH minimises wakeups for a long-lived scan (per Android BLE battery guidance).
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — OS-held wake beacon (battery-efficient background, additive/revertable)
The battery-efficient, OS-like way to stay reachable when the receiver process is killed
(closest a non-privileged app gets to Quick Share). ADDITIVE — the continuous presence
advertise is unchanged, so today's flow still works; this only adds wake resilience.
- `trigger/WakeBeacon.java`: SENDER advertises a per-flavor `BEACON_UUID`
  (`PresenceAdvertiser.BEACON_UUID`, e.g. 0000a1eb) while the send sheet is open; RECEIVER
  registers a SYSTEM-HELD scan (`startScan(filters, settings, PendingIntent)`, MUTABLE on
  31+) for it — the OS fires the PendingIntent even if our process was killed.
- `ui/BeaconWakeReceiver.java` (manifest receiver): on the wake broadcast → `ReceiveService.start`
  (re-arm). try/catch so an OEM block on background FGS-start degrades silently.
- `ReceiveService.armCheapListeners` registers the wake scan; it is unregistered only when
  receive is turned OFF/expires (NOT on onDestroy — it must survive process death).
- `EngineSendController` start/stop the beacon advertise with the scan.
- All three flavors build SUCCESSFUL; APKs refreshed. NEEDS phone verify: does a killed
  receiver get woken by a sender beacon (and is the FGS-start from the broadcast allowed)?

### 2026-05-30 — onTaskRemoved restart + send-sheet grow/overscroll animation
- `ReceiveService.onTaskRemoved`: restart the service if receive != OFF, so swiping the
  app from recents doesn't kill receive. (Crude 15-min keep-alive alarm was tried then
  reverted; the proper battery-efficient background = scan-with-PendingIntent role-flip,
  tracked in the TODO memory.)
- `DraggableSheetLayout.animateGrow()` + `SendSheetActivity` calls it when a device
  appears: the sheet slides up to its new height with a slight overscroll settle
  (translationY overshoot), not a pop or a scale-bounce.
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Received photos/videos now appear in Gallery (MediaStore insert)
Received media is published into MediaStore so Gallery/Photos shows it (scoped-storage
correct; raw File write to Pictures/ is EPERM).
- `ReceiveService`: snapshot dir names before the pull; on complete, NEW image/video
  files are inserted via `ContentResolver` into `MediaStore.Images`/`Video` with
  `RELATIVE_PATH=Pictures|Movies/BridgeShare` (IS_PENDING workflow, bytes copied), then
  the Download copy is deleted. Non-media files stay in Download + MediaScanner.
- "View"/"Open" opens the published media content uri directly (`sLastMediaUri`).
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Repeat-join race fix (pre-clear stale P2P group) + send-sheet X close
Logs showed repeat receives failing `connect onFailure reason=0` (even at importance
100) or `onSuccess` then no `groupFormed` — a stale Wi-Fi-Direct group/state race.
- `WifiP2pConnector.clearStaleGroup(ctx)`: fire-and-forget `removeGroup` on a fresh
  channel, called from `ReceiveService.onCredsWritten` WHEN THE PROMPT APPEARS — so by
  the time the user taps Accept the supplicant is idle and the synchronous Accept-time
  connect() isn't rejected. Kept OFF the connect() path (stays synchronous in the hop).
- `SendSheetActivity`: small "✕" close button in the top-right of the sheet (dismiss).
  (Device-icon statuses Connecting/Sending/Sent/Failed already shown.)
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Non-blocking overlay + instant invisible foreground hop + no app-stranding
Per user: overlays don't have to block touch; the connect needs foreground only AT
the Accept instant; and the hop must not strand you in the app.
- `OverlayReceiver`: shrunk the overlay window to a ~320dp top band (was 440) with
  `FLAG_NOT_TOUCH_MODAL` so you can scroll/tap the app BELOW the floating card
  (like a screen-dimmer / pop-up overlay).
- `WifiP2pConnector.connect`: dispatch `connect()` SYNCHRONOUSLY (dropped the async
  pre-`removeGroup`, which delayed the call past the hop's lifetime; the reason=0 it
  targeted was the importance-125 background issue, now fixed by running from a
  foreground activity).
- `TransferAcceptActivity`: invisible (no bar/scrim); in `onResume` it dispatches the
  connect at importance 100 then `finish()`es IMMEDIATELY — nothing lingers after
  Accept. Runs in its own throwaway task (manifest `taskAffinity=""` +
  excludeFromRecents) so it doesn't drag MainActivity forward and returns the user to
  what they were doing; transfer continues in the background.
- Manifest: `taskAffinity=""` on `ReceiveBottomSheetActivity` + `TransferAcceptActivity`
  so neither strands the user in the app.
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — "View"/"Open" on the receive card opens the actual received file
- `ReceiveService.openReceived(ctx)`: opens the most-recently-received file in
  Download/BridgeShare — a photo/video opens in the default viewer (ACTION_VIEW on
  its MediaStore content uri + image/video mime, captured during the media scan);
  anything else opens the system Downloads view (ACTION_VIEW_DOWNLOADS).
- Wired both receive surfaces' Open/View action to it (`ReceiveBottomSheetActivity`
  onOpen, `OverlayReceiver` onOpen) — replaces the old generic "open gallery".
- All three flavors build SUCCESSFUL; APKs refreshed. (Foreground-surface choice
  still pending user decision; no change to it here.)

### 2026-05-30 — Foreground only AT Accept (invisible hop, dismiss on join) + send double-tap guard
Key insight (user-observed, matches the proof): the foreground activity is needed
ONLY at the moment of Accept for connect(); once connected the transfer continues
in the background. So no persistent foreground / dim is needed.
- `ReceiveUi.show`: overlay enabled → overlay card + Accept fires a brief INVISIBLE
  foreground hop (`TransferAcceptActivity`, no scrim) just to reach importance 100
  for connect(); overlay disabled → foreground bottom sheet.
- `TransferAcceptActivity`: removed the dim scrim (fully transparent); dismissed via
  `dismissNow()` the instant the connection is established.
- `ReceiveService.joinAndPull` `onJoined` → `TransferAcceptActivity.dismissNow()` so
  the hop goes away right after connect and the download runs in the background.
- `SendSheetActivity`: guard device-icon tap (one send per sheet; a 2nd tap used to
  re-enter sendTo and looked like a cancel).
- Device-admin / ADB-appops / touch-through dim-overlay ruled out (none grant the
  importance-100 visible-activity state connect() requires).
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — PROVEN: connect needs foreground activity → route receive to bottom sheet; fix send double-tap
Process-importance logging settled it on-device, back-to-back: `importance=125
(FOREGROUND_SERVICE)` → `connect onFailure reason=0` (×5); `importance=100
(FOREGROUND)` → `connect onSuccess` + completed transfer. Confirmed by Android docs
(a foreground service is a "visible process", strictly below a foreground activity;
notification importance is a separate system and does NOT raise process priority).
So a foreground service / louder notification cannot initiate Wi-Fi-Direct connect()
on ColorOS — a visible activity is required.
- `ReceiveUi.show`: always route to the foreground bottom-sheet activity
  (`ReceiveBottomSheetActivity`, which adopts the real controller) so Accept runs the
  join at importance 100. Overlay card is no longer the connecting surface (it can't
  be — not an activity). Background activity-start is permitted via SYSTEM_ALERT_WINDOW.
- `SendSheetActivity`: guard the device-icon tap — once a send is underway, further
  taps are ignored (a 2nd tap used to re-enter sendTo and looked like a cancel).
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Revert save dir to Download (fix EPERM), pure-FGS receive path + connect-time importance log
Foreground test logs (with the new logging) proved the join SUCCEEDS
(`connect onSuccess`) — the failure was the file WRITE: `EPERM (Operation not
permitted)` writing the received `.apk` to `Pictures/BridgeShare/`. Under scoped
storage (targetSdk 35, no legacy) a raw File write to Pictures/ is denied for
arbitrary/non-media files. So the "Gallery" move was a regression that broke the
transfer.
- `ReceiveService.receivedDir()`: reverted to `DIRECTORY_DOWNLOADS` (raw File writes
  are accepted there — that's why earlier transfers worked). Gallery-visible media
  would need the MediaStore insert API (separate task).
- `OverlayReceiver`: Accept now calls `controller.accept()` directly (pure
  foreground-SERVICE path) — no forced `TransferAcceptActivity`. We measure rather
  than assume an activity is required.
- `WifiP2pConnector.logProcessState()`: logs this app's ActivityManager process
  importance at connect time (100=FOREGROUND / 125=FOREGROUND_SERVICE /
  200=VISIBLE / 400=CACHED-BACKGROUND) so a background test PROVES whether the FGS
  is foreground-enough vs. needing an activity.
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Comprehensive UI/lifecycle logging
Added end-to-end UI logging so the diagnostic log shows the full story (foreground/
background transitions, overlay state machine, pill sizing).
- NEW `BridgeApp` (Application, registered in manifest): inits DiagLog early and logs
  EVERY activity lifecycle app-wide (onCreate/Start/Resume(FOREGROUND)/Pause/
  Stop(BACKGROUNDED)/Destroy) via ActivityLifecycleCallbacks.
- `OverlayCard`: logs ackContent / beginLiveProgress / liveProgress% / liveComplete /
  liveCanceled / collapseToPill (with the actual card px size at collapse — pins down
  the "thin pill") / expandFromPill / dismiss.
- `OverlayReceiver`: logs show + window-added (band px) + addView failure.
- All flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Battery-optimization exemption (keep transfers alive when backgrounded)
User found that accepting in the foreground then leaving the app stops the transfer
mid-flight — ColorOS freezes the foreground-service process in the background.
Making the prompt an Activity doesn't help (leaving any activity backgrounds the
app). The standard lever is exempting the app from battery optimization.
- Manifest: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- `MainActivity`: "Allow background transfers (disable battery optimization)" button
  → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; label reflects
  `PowerManager.isIgnoringBatteryOptimizations`.
- NOTE: OnePlus/ColorOS may also need the per-app "Allow background activity"/
  "Auto-launch" toggle (not covered by the AOSP exemption). Pending phone verify
  whether the exemption alone keeps the transfer running after leaving the app.
- All flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Foreground hop shows a dim "shader" instead of the app UI
`TransferAcceptActivity` now renders a translucent dark scrim (0x99000000, tap to
dismiss) so accepting from the background reads as "the screen dims for the
transfer" with the floating card on top — rather than yanking the user into the
app. (A separate activity can't composite the user's actual previous app behind it,
so it's a dark scrim, not the literal prior screen dimmed.) Still provides the
foreground status the Wi-Fi-Direct connect() needs. All flavors build; APKs refreshed.

### 2026-05-30 — Fix background join failure: foreground hop on overlay Accept
User confirmed the Wi-Fi-Direct join (`connect onFailure reason=0`) fails ONLY when
the receiver is in the background, never in the foreground. Cause: Android (and
ColorOS) blocks initiating `WifiP2pManager.connect()` from a background app; the
overlay receive path floats over another app so the receiver process stays
background, while the bottom-sheet path is a real foreground Activity (and works).
- NEW `ui/TransferAcceptActivity.java`: transparent, no-UI. The overlay card's
  Accept now launches it (FLAG_ACTIVITY_NEW_TASK); from `onResume` (app now
  foreground) it runs the real `controller.accept()` so `connect()` is dispatched
  with foreground privilege, then self-finishes after 15s (the P2P link persists
  once initiated). The overlay card stays on top showing progress.
- `OverlayReceiver` Accept → `TransferAcceptActivity.launch(...)` instead of a
  direct background `controller.accept()`.
- Manifest: registered `TransferAcceptActivity` (translucent, excludeFromRecents).
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Gallery (Pictures) + sender "Sent" state + send→receive re-arm + connect retry
Follow-ups after the verified transfer + more real-phone feedback:
- Received media now saved to `Pictures/BridgeShare` (gallery-indexed) instead of
  `Download/` (Gallery/Photos surface Pictures+DCIM, not Download — why a received
  image showed in the file manager but not the gallery). Media scan confirmed
  working (files got `content://media/...` URIs).
- Sender stayed on "Sending" forever — the host never knew the transfer finished.
  `ShareHttpServer` now fires `onAllServed` once every offered item is fully
  downloaded → `BridgeEngine` host path → `Events.onComplete` → sender shows "Sent".
- `SendSheetActivity.onDestroy` re-arms `ReceiveService` (if receive enabled) so the
  device returns to receiving after a send (it wasn't re-arming).
- `WifiP2pConnector` retries the connect up to 4× (1.5s apart) on transient
  reason=0/2 failures (NOTE: user reports the join only fails in the BACKGROUND —
  a separate foreground-requirement fix follows).
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Transfer VERIFIED end-to-end; make received files show in Gallery + reliable repeat joins
First confirmed real transfer: host served the manifest and sent all 482998 bytes
(`download done … sent 482998 bytes`), receiver wrote the file (`done: …jpg`),
byte-exact over Wi-Fi Direct. Two follow-ups from the same logs:
- Received file was invisible in Gallery — `DownloadClient` writes
  `Download/BridgeShare` with plain file I/O and never registers it with MediaStore.
  `ReceiveService.onComplete` now calls `scanIntoGallery(saveDir)`
  (`MediaScannerConnection.scanFile`) so received media appears in Gallery/Photos.
- Repeat/backgrounded joins failed with `connect onFailure reason=0` (a stale P2P
  group blocks a fresh connect; 1st/4th sends worked, 2nd/3rd didn't).
  `WifiP2pConnector.connect` now `removeGroup` first (success or failure → connect)
  to clear stale group state before each join.
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — FINAL transfer blocker: allow cleartext HTTP (P2P pull was blocked by Android policy)
With the host server now stable, the receiver joined (`joined host 192.168.49.1:2999`)
but the pull failed instantly: `java.io.IOException: Cleartext HTTP traffic to
192.168.49.1 not permitted` (okhttp CleartextURLFilter ← DownloadClient.fetchManifest).
The transfer is plain HTTP on port 2999 and Android 9+/minSdk29 blocks cleartext by
default, so the manifest GET was rejected before leaving the joiner (host saw zero
`accepted connection`).
- `AndroidManifest.xml`: `android:usesCleartextTraffic="true"` on <application> — this
  is the last thing between a successful P2P join and the actual file transfer.
- `AndroidManifest.xml`: added `USE_EXACT_ALARM` (the TIMED 10-min mode's
  `setExactAndAllowWhileIdle` was throwing SecurityException).
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Make host start the HTTP server ONCE (kill EADDRINUSE from repeated P2P broadcasts)
Even after the resend-teardown fix, the host still hit `EADDRINUSE` on 2999: the
Wi-Fi-P2P `CONNECTION_CHANGED` broadcast fires `onCredsReady` repeatedly (once per
event), and each call rebound a fresh HTTP server, racing into the bind error — so
no server stayed listening and the (successful) join had nothing to pull from
(`connect onSuccess`/`groupFormed=true` but zero `accepted connection` on the host).
- `BridgeEngine.startHostShare`: guard `onCredsReady` with an `AtomicBoolean` so the
  server start + creds publish happen exactly once per host session; repeat
  broadcasts are ignored.
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Fix resend pile-up (host EADDRINUSE) + overlay swallowing all touch
Logs showed the Wi-Fi-Direct join now SUCCEEDS (`connect onSuccess`,
`groupFormed=true owner=192.168.49.1`), but the host logged
`java.net.BindException: EADDRINUSE` on port 2999: each `sendTo` built a new
BridgeEngine whose HTTP server re-bound the port without stopping the previous
one. User confirmed the trigger — transfers kept failing so they kept resending,
piling up requests. With no host server the pull fails → more resends → stuck
overlays.
- `ui/EngineSendController.java`: `sendTo` now stops the previous engine + GATT
  writer before starting a new attempt (no more port leak on resend).
- `ui/OverlayReceiver.kt`: the overlay window was full-screen with no scrim, so it
  swallowed every touch (only status bar/back worked) and could hang. Changed to
  WRAP_CONTENT height + `Gravity.TOP` + `FLAG_NOT_TOUCH_MODAL` so only the card
  band is touchable and taps below pass through to the app; auto-remove the card
  8s after complete / 2s after cancel so it can never hang on screen.
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Fix cross-flavor BLE contamination crash (Direct receiver got Hotspot creds)
With Accept now reaching the real join, a new FATAL surfaced: receiver
`com.bridge.share.direct` got `creds written ssid=AndroidShare_3124 ip=192.168.1.1`
→ `WifiDirect: connect to AndroidShare_3124` → `IllegalArgumentException: network
name must starts with the prefix DIRECT-xy` at `WifiP2pConfig.Builder.setNetworkName`
(main thread → app died). Root cause: all three flavors shared the SAME BLE presence
service/char UUID (0000a1ee/ef), so a Hotspot-flavor sender discovered and wrote
hotspot creds to the Direct-flavor receiver, whose Wi-Fi-Direct engine rejects a
non-DIRECT- ssid.
- `trigger/PresenceAdvertiser.java`: per-flavor UUIDs (DIRECT 0000a1ee/ef unchanged,
  HOTSPOT 0000a3ee/ef, AWARE 0000a2ee/ef) so flavors never cross-discover/trigger.
- `conn/WifiP2pConnector.java`: `doConnect` now try/catches the WifiP2pConfig build
  → `finish()`/`onResult(false)` instead of crashing on a bad ssid (defense in depth).
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — FIX the real "nothing sends": receive Accept was wired to DEMO code on both paths
Round-2 real-phone logs confirmed the MTU fix works end-to-end (receiver decodes
full creds: `request-host write len=144 -> creds written ssid=AndroidShare_… ip=…`).
But after the accept card showed, NOTHING followed (no join, no download) — Accept
was a no-op because it was wired to demo code on BOTH presentations:
- `ReceiveBottomSheetActivity` hardcoded `new PreviewReceiveController()` and
  `ReceiveUi.startSheet` dropped the real controller (can't ride an Intent).
- `OverlayCard`'s Accept button called `startProgress()` — a fake local progress
  timer (the 1:1 card.html demo) — never the engine.

Fixes:
- `ReceiveUi`: hand the real controller to the sheet via a static `pending` slot
  (`consumePending()`); `ReceiveBottomSheetActivity` adopts it (demo only on a
  direct preview launch).
- `OverlayCard`: added `onAccept` callback + live methods `beginLiveProgress`/
  `liveProgress`/`liveComplete`/`liveCanceled`; Accept now calls `onAccept()` and
  shows progress WITHOUT the fake timer.
- `OverlayReceiver`: wires `onAccept -> controller.accept()` and binds a
  `ReceiveController.Ui` that drives the card from real engine progress/complete/
  cancel. Also switched the overlay ContextThemeWrapper to
  `Theme_AppCompat_Light_NoActionBar` to fix the `LottieAnimationView ... Theme.AppCompat`
  error on the complete state.
- Accept-path logging: `EngineReceiveController.accept/decline/cancel`,
  `ReceiveService.joinAndPull`, and the adopted controller class in the sheet.
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Host-side HTTP request logging (see receiver's pull from the sender's reliable upload)
Real-phone DiagLog from CPH2515 confirmed the MTU fix works (onMtuChanged mtu=517,
writeCharacteristic len=138/144, "creds write success" for Direct + Hotspot). But
the receiver (CPH2583) didn't upload — it can't reach the collector while joined to
the host's P2P/hotspot interface (no internet there). The host uploads reliably, so:
- `channel/ShareHttpServer.java`: log accepted connections (remote addr), each
  request line, manifest served (item count), and per-download start + bytes sent
  (plus 404-no-record and aborted-download warnings). This makes the SENDER's log
  show whether the receiver actually connected and pulled the file.
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Fix DiagLog OOM crash (unbounded log queue) that could kill the sender mid-transfer
Real-phone SIGABRT in ART's MarkCompact GC (`mremap to move pages failed: Out of
memory`, ~256MB, on HeapTaskDaemon of com.bridge.share.direct). Cause: DiagLog's
log queue was unbounded and the logcat capture had no level filter, so it ingested
the heavy framework DEBUG spam (D/VRI, D/BufferQueue, D/OplusScrollToTop); when the
upload fell behind, the queue grew until the heap couldn't compact. If the crashing
process is the sender, this also presents as "triggered but nothing sent."
- `diag/DiagLog.java`: bound the queue (`MAX_QUEUE=4000`, drop-oldest on overflow),
  cap line length (2000), and filter the logcat capture to Info+
  (`logcat -v time --pid=<mypid> *:I`) — keeps all our Log.i/Log.w + engine logs,
  drops the framework DEBUG noise.
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — FIX root cause of "triggered but nothing sent": BLE creds write was MTU-truncated + logging on/off toggle
First real-phone test (2 OnePlus) diagnosed via DiagLog uploads. Findings:
- **WIFI_DIRECT + HOTSPOT**: host came up correctly (group/hotspot formed, HTTP
  server listening, full creds built) and the sender dispatched the creds GATT
  write (`len=138`/`len=144`), but the receiver only ever saw `len=18` and
  decoded `ssid=null ip=null` → joined a null host → no transfer. Cause: the
  default BLE ATT MTU (23) caps a Write Request at ~20 bytes, truncating the
  ~140-byte creds JSON.
- **WIFI_AWARE**: `No service published for: wifiaware` / `WifiAwareManager
  unavailable` — these phones have no Wi-Fi Aware radio, so that flavor can't
  work on them (hardware limitation, not a bug).

Changes:
- `trigger/CredsGattWriter.java`: on GATT connect, `requestMtu(517)` and wait for
  `onMtuChanged` before discoverServices + write, so the full ~140-byte creds fit
  in one Write Request. THE fix for the failed transfer.
- `ui/ReceiveService.java`: `onCredsWritten` now validates the decoded creds
  (ssid+ip for Direct/Hotspot, or serviceName for Aware) and ignores
  truncated/garbage writes instead of flipping `transferActive` and blocking the
  real write.
- `diag/DiagLog.java`: added an on/off switch — `isEnabled`/`setEnabled`/`stop`
  backed by a persisted pref (default ON). `setEnabled(false)` stops the logcat
  capture (destroys the logcat process) and the uploader; `init` honours the pref.
- `ui/MainActivity.java`: new settings-page button "Diagnostic logging: ON/OFF"
  that toggles `DiagLog.setEnabled` and reflects state (also refreshed in onResume).
- All three flavors build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Verbose remote diagnostics (DiagLog) to debug real-phone send failures
User reported all three flavors fail to actually send on real phones. Added a
debug-only diagnostic logger that captures the app's own logcat and auto-uploads
it so the failure point is visible without physical access to the devices.

- NEW `diag/DiagLog.java`: `init(ctx)` (idempotent) captures THIS process's own
  logcat (`logcat -v time --pid=<mypid>` — own-pid logs need no READ_LOGS),
  mirrors every line to app-private `getExternalFilesDir()/diag/diag-<session>.log`,
  and POSTs batches (~3s) to a collector. Uploads are bound to an
  INTERNET+VALIDATED `Network` so a Wi-Fi-Direct group owner's P2P interface
  can't steal the default route and silently drop the logs. `DiagLog.d(tag,msg)`
  adds explicit points at otherwise-silent branches.
- `init()` wired into `MainActivity`, `SendSheetActivity`, `ReceiveService`
  onCreate (covers send + receive entry points).
- Extra log points: `MainActivity.onRequestPermissionsResult` (which perms were
  GRANTED/DENIED — the prime suspect for a silent no-op), `EngineSendController`
  (BLE perm + BT-enabled state, `PresenceScanner.start()` boolean result, records
  built with names/sizes/uris), `SendSheetActivity` (peer found, tap→sendTo, and
  the ERROR message that the UI otherwise swallows behind "Failed").
- Collector tooling under `diagserver/`: `collector.py` (stdlib http.server on
  127.0.0.1:8770, `POST /log` → `logs/<device>__<engine>.log`) exposed via a
  cloudflared quick tunnel. NOTE: trycloudflare URLs are ephemeral; the live one
  is hard-coded in `DiagLog.ENDPOINT` and must be re-patched + rebuilt if the
  tunnel restarts.
- All three flavors build SUCCESSFUL; APKs refreshed; DiagLog + endpoint verified
  present in the packaged dex.

### 2026-05-30 — Crash fix (runtime BLE perms) + granted-Network socket binding + QS tile + compile fixes
Fixes the FATAL `SecurityException: Need BLUETOOTH_CONNECT` crash reported on a
real phone (com.bridge.share.direct) when `ReceiveService` armed presence, binds
the transfer sockets to the engine's granted Network (Aware/Hotspot), adds a
Quick Settings tile, and fixes two compile errors that were blocking the build.

- `ui/MainActivity.java`: added `requestNeededPermissions()` (called from
  `onCreate`) requesting ACCESS_FINE_LOCATION + BLUETOOTH_SCAN/CONNECT/ADVERTISE
  (API 31+) + NEARBY_WIFI_DEVICES/POST_NOTIFICATIONS/READ_MEDIA_* (33+) via
  `requestPermissions(...,1001)`. Without this the receiver had no way to be
  granted the dangerous BLE perms → the service crashed on arm.
- `trigger/PresenceAdvertiser.java`: added static `hasBlePerms(Context)`
  (checks BLUETOOTH_CONNECT+ADVERTISE on API≥31, true below); `start()` now
  early-returns false when perms are missing and is wrapped in try/catch
  (SecurityException/Exception → `stop()` + return false) so a missing grant can
  never crash the foreground service. Same guard added to `CredsGattWriter.write`
  and `CredsBleServer.start`.
- `channel/ShareHttpServer.java`: optional `Network` ctor arg; when supplied the
  listening `ServerSocket` is created from a `ServerSocketChannel` whose FD is
  bound via `Network.bindSocket(fd)` (Network has no ServerSocket overload), so
  the host serves on the granted Aware/Hotspot interface.
- `channel/DownloadClient.java`: routes connections through the granted Network
  via `Network.openConnection(url)`, brackets IPv6 literal hosts (Aware
  data-path). **Compile fix:** added the missing `volatile boolean cancelled`
  field (was referenced by `cancel()`/`run()`/`download()` but undeclared).
- `channel/ShareChannel.java`: Network/SocketFactory/isIpv6 client overload.
  **Compile fix:** the 4-arg `startClient` delegated with the wrong argument
  order (listener landed in the `network` slot); reordered to
  `startClient(hostIp, port, saveDir, null, null, false, listener)`.
- `BridgeEngine.java`: on connect, rebinds the host server to the endpoint's
  granted `Network`.
- NEW `ui/ReceiveTileService.java` + manifest `<service>` (BIND_QUICK_SETTINGS_TILE):
  Quick Settings tile toggling OFF↔ALWAYS_ON.
- `ui/OverlayCard.kt`: added the `.12s` (120ms LinearInterpolator) arc tween.
- All three flavors (direct/aware/hotspot) build SUCCESSFUL; APKs refreshed.

### 2026-05-30 — Wire REAL engine into send/receive UI + presence discovery + on-demand battery model
Replaces the DEMO controllers on the live paths with the real engine, adds a BLE
presence-discovery piece, and implements oshare-port's on-demand (battery) host
model in `ReceiveService` per `docs/BATTERY_SPEC.md`.

- NEW `trigger/PresenceAdvertiser.java`: cheap receiver-side BLE presence. Advertises
  presence `SERVICE_UUID 0000a1ee` (LOW_POWER, TX_POWER_LOW, connectable,
  `setIncludeDeviceName(false)`) with the short alias as service-data; opens a GATT
  server with a WRITABLE request characteristic `0000a1ef`. `start(alias,onCredsWritten)` /
  `stop()`; the write handler fires `onCredsWritten(byte[])`. Distinct from the
  high-power post-trigger `CredsBleServer` (0000a1ec/0000a1ed).
- NEW `trigger/PresenceScanner.java`: sender-side BLE scan (LOW_LATENCY) filtered to
  `0000a1ee`, deduped by device address, alias read from service-data → `onFound(addr,alias)`.
  `start(cb)`/`stop()`; scan-stop is the caller's job.
- NEW `trigger/CredsGattWriter.java`: `write(address, credsBytes, cb)` — connectGatt →
  discoverServices → write to char `0000a1ef` on service `0000a1ee` → close.
- NEW `ui/EngineSendController.java` (implements SendController): `startScan` → PresenceScanner
  (onFound → `onPeerFound(Peer(address,alias))`); `sendTo(peer,uris)` builds `ShareRecord`s
  (DISPLAY_NAME/SIZE via ContentResolver, localUri=uri.toString, generated record/item id),
  stops scan, `new BridgeEngine(ctx,MODEL).startHostShare(records,Events)`; on
  `onHostPublished(creds)` → `CredsGattWriter.write(peer.id, creds.toBytes())`; maps
  engine onProgress/onComplete/onError to the listener. `stop()` tears down.
- NEW `ui/EngineReceiveController.java` (implements ReceiveController): forwards engine
  progress/complete/cancel into the card and accept/decline/cancel back to ReceiveService
  via a `Decision` callback. (PreviewReceiveController kept for the preview button.)
- `ui/SendSheetActivity.engineController()` now returns `new EngineSendController(this)`
  (real); PreviewSendController kept ONLY when `uris.isEmpty()` (preview button).
- `BridgeEngine`: added PUBLIC `joinWithCreds(Creds, File saveDir, Events)` (delegates to
  the existing private logic, renamed `joinWithCredsInternal`) so ReceiveService can join
  on-demand without re-running BLE/NFC discovery.
- REWROTE `ui/ReceiveService.java` to the on-demand model (BATTERY_SPEC §2/§4/§5):
  ARMED = cheap only (PresenceAdvertiser LOW_POWER + `CredsHceService.armTrigger()`,
  NO hotspot, NO wake lock). Sender's BLE GATT request-host WRITE → `onCredsWritten` →
  partial WakeLock + show real receive card; on Accept → `BridgeEngine.joinWithCreds`
  into `Downloads/BridgeShare` → drive card progress/complete. Replaced the
  `Handler.postDelayed` 10-min stop with `AlarmManager.setExactAndAllowWhileIdle`
  (ELAPSED_REALTIME_WAKEUP) firing new `ACTION_EXPIRE` (full teardown + setMode(OFF) +
  stopForeground/stopSelf), cancel-then-set on each start. Added ~90s post-trigger /
  ~30s post-join idle watchdogs; teardownToArmed on complete/decline/error; fullTeardown
  on OFF/expire/onDestroy. ACTION_EXPIRE handled in onStartCommand (explicit Intent, no
  manifest change). Existing BLE perms cover everything; manifest unchanged.

### 2026-05-30 — Wire the 3 connection variants into the engine (no more dead code)
- Added `conn/ConnectionFactory.kt`: `create(Context): Connection` selecting the variant
  by `BuildConfig.ENGINE` ("WIFI_AWARE"→WifiAwareConnection, "HOTSPOT"→HotspotConnection,
  else WifiDirectConnection), each via its `(Context)` constructor.
- Rewrote `BridgeEngine.java` to talk to the `Connection` interface (via
  ConnectionFactory) instead of the now-dead `BridgeConnection`. Host:
  `onCredsReady(desc)` → start ShareChannel + publish `Creds` (BLE advertise + NFC HCE),
  using the channel's real listen port; `onConnected` logs link-up. Joiner: rebuild a
  `ConnDescriptor` (variant = build flavor) from received `Creds` → `conn.join` →
  `onConnected(ep)` → `channel.startClient(ep.host/ep.port,…)`.
- Extended `trigger/Creds` with `serviceName` + `pskPassphrase` (JSON round-trip) so the
  Wi-Fi Aware descriptor (no ssid/ip) survives the BLE/NFC creds hop.
- `BridgeConnection.java` kept but unused. Public `BridgeEngine.Events` API unchanged.
- KNOWN LIMITATION (not fixed — out of scope): `ShareHttpServer`/`DownloadClient` use a
  plain ServerSocket / HttpURLConnection and do NOT bind to `Endpoint.network` /
  `socketFactory`. So the Aware-granted IPv6 Network and the Hotspot-granted Network are
  not bound to the transfer sockets; engine logs a warning when `ep.network != null`.

### 2026-05-30 — Engine interface + 3 connection variants + overlay receive path
- Engine seam: `Connection` interface + `ConnDescriptor` + `Endpoint` (conn/), per
  docs/ENGINE_SPEC. Three variants implemented (one file each, via sub-agents):
  `WifiDirectConnection` (wraps WiDiNetworkManager/WifiP2pConnector),
  `WifiAwareConnection` (NAN, APIs javap-verified vs android.jar), `HotspotConnection`
  (LocalOnlyHotspot host + WifiNetworkSpecifier joiner). Not yet selected/wired into
  the engine controller — that's the next step.
- Overlay receive: factored the receive card into a shared `ReceiveCard` (used by both
  presentations). `OverlayReceiver` hosts it in a TYPE_APPLICATION_OVERLAY window;
  `ReceiveUi` dispatches overlay-vs-bottom-sheet on Settings.canDrawOverlays.
  ReceiveBottomSheetActivity slimmed to use ReceiveCard. MainActivity: receive preview
  routes through ReceiveUi; added an Enable-overlay button (ACTION_MANAGE_OVERLAY_PERMISSION)
  with live ON/OFF label. BUILD SUCCESSFUL.

### 2026-05-30 — Receive accept prompt (bottom sheet) + Bluetooth re-arm
- Always-show accept gate (even for NFC). `IncomingTransfer` model; `ReceiveController`
  seam (onIncoming/onProgress/onComplete/onCanceled + accept/decline/cancel);
  `PreviewReceiveController` (DEMO "Demo Pixel" incoming for review).
- `ReceiveBottomSheetActivity`: default presentation — translucent slide-up sheet with
  Accept/Decline → progress → "Transfer complete" (OK/View). Launched directly it uses
  the preview controller.
- `BtStateReceiver`: runtime receiver (ACTION_STATE_CHANGED is NOT manifest-exempt, so
  registered dynamically by ReceiveService) — on BT ON with mode Always-on, re-arms.
- MainActivity: "Preview receive sheet" button. Manifest: ReceiveBottomSheetActivity +
  SYSTEM_ALERT_WINDOW (for the overlay path). Verified BUILD SUCCESSFUL.
- STILL TODO for task #16: the overlay-card presentation (TYPE_APPLICATION_OVERLAY, used
  instead of the bottom sheet when overlay permission is granted) + the quick-settings
  TileService + the ReceiveUi dispatcher (overlay vs bottom sheet).

### 2026-05-30 — Send bottom sheet UI + engine/battery specs
- `SendSheetActivity`: share-sheet target (ACTION_SEND/SEND_MULTIPLE, translucent
  bottom sheet, slides up, tap-scrim to dismiss). Immediately "scans"; discovered
  devices render as circular `DeviceIconView`s; tapping bounces the icon, shows a
  status line, and a ring progress.
- `RingProgressView`: circular blue avatar + glyph + white progress arc (12 o'clock).
- `DeviceIconView`: ring + name + status, with an overshoot bounce on tap.
- `SendController` interface (UI↔engine seam) + `PreviewSendController` (clearly
  labelled DEMO devices "Demo Pixel/Galaxy" for reviewing the interaction without a
  second phone or the engine). Real engine controller is a TODO per docs/ENGINE_SPEC.
- MainActivity: "Preview send sheet" button to review the send UI directly.
- Manifest: SendSheetActivity with SEND/SEND_MULTIPLE filters. Verified BUILD SUCCESSFUL.
- docs/ENGINE_SPEC.md (Connection interface + 3 variants: Wi-Fi Direct / Wi-Fi Aware /
  hotspot) and docs/BATTERY_SPEC.md (on-demand bring-up, per-state radio table) added
  by spec sub-agents. NOTE: BATTERY_SPEC flags that ReceiveService's 10-min stop uses
  handler.postDelayed (not Doze-safe) — must move to AlarmManager.setExactAndAllowWhileIdle.

### 2026-05-29 — Pivot to from-scratch app + main settings page UI
Project repurposed from "SHAREit clean engine" into the from-scratch P2P share app
(SHAREit graft abandoned; see memory project_p2p_share_app_spec_2026_05_29). The
conn/channel/trigger layers are kept and reused. New UI:
- `ReceivePrefs`: persists the single receive-visibility setting — OFF / ALWAYS_ON /
  TIMED (10 min). TIMED auto-expires (battery-friendly); only ALWAYS_ON survives reboot.
- `MainActivity`: the single main page (Off / Always on / On for 10 minutes), like
  oshare-port; selecting a mode persists it and starts/stops the receive service.
- `ReceiveService`: foreground service (connectedDevice type) gated by the setting;
  TIMED self-stops after the 10-min window. Host/BLE/HTTP wiring is a TODO for the
  receive-UI task.
- `BootReceiver`: re-arms the service after reboot only when ALWAYS_ON.
- Manifest: added the service (foregroundServiceType=connectedDevice), boot receiver,
  RECEIVE_BOOT_COMPLETED. Verified BUILD SUCCESSFUL.
Connection-method variants (Wi-Fi Direct / Wi-Fi Aware / nearby+hotspot) to follow.

### 2026-05-29 — Transfer layer (HTTP wire protocol) reconstructed, compiles
Faithful reconstruction of SHAREit's transfer wire protocol into
`com.bridge.share.channel` (clean Java, not a transplant of SHAREit's
content-library/servlet/STP-native subsystem — per the agreed "reconstructed
faithfully" scope):
- `ShareRecord` (= SHAREit ShareRecord, reduced to the fields the /download URL
  keys on: recordid/metadataid/metadatatype/filetype + local descriptors).
- `ShareHttpServer` (= NBg HttpServer): ServerSocket + cached thread pool on the
  SHAREit channel port 2999; endpoints `GET /msg/collection` (manifest) and
  `GET /download?recordid&metadatatype&metadataid&filetype=raw&position=N`
  (byte-position resume). Serves content:// and file paths. TCP (SHAREit's STP
  fallback path; STP itself is a native .so).
- `DownloadClient` (= DownloadTask): pulls the manifest then GETs each item with
  SHAREit's exact /download URL build + position resume; progress callbacks.
- `ShareChannel` (= DefaultChannel role): host runs the server, client runs the
  downloader. SHAREit's separate TCP message channel (VDg) is folded into the
  HTTP manifest endpoint.
- Verified `BUILD SUCCESSFUL`.

### 2026-05-29 — Connection layer (the bridge) ported, compiles
Bite-for-bite port of SHAREit's Wi-Fi-Direct connection into `com.bridge.share.conn`:
- `WorkMode` (= SHAREit WorkMode, single-letter tokens), `NetworkStatus`, `Device`
  (obfuscated fields given recovered names).
- `HashUtils` (= C8768eFd): `md5Hex` for the passphrase derivation.
- `SsidHelper` (= C11158jIg + C17770xHg helpers): 62-char alphabet, subnet map,
  `derivePassword`, `randomCharForSsid`, `bandPrefix`, `buildDirectNetworkName`
  ("DIRECT-<band><c>-<body>", ≤32), `directPassphrase` (md5Hex(name)[:8] / "12345678"
  INVITE), identity-body encode/decode, SSID predicates.
- `WifiP2pConnector` (= JIg): CLIENT no-dialog join — `connect(config{networkName,
  passphrase,persistent=false})` then requestConnectionInfo → group-owner IP.
- `WiDiNetworkManager` (= MHg): HOST `createGroup(config{DIRECT-name, md5 passphrase,
  band, persistent=false})` → group-owner IP (192.168.49.1).
- `BridgeConnection`: trigger-driven orchestrator (WifiMaster role minus SHAREit's
  scan/radar discovery, which the NFC/BLE trigger replaces). startHost / join / stop.
- Verified `BUILD SUCCESSFUL`.

### 2026-05-29 — Scaffold (v1.0 / versionCode 1)
- Created standalone single-module Gradle project (AGP 8.5.2, Gradle 8.7, JDK 17,
  compileSdk 35, minSdk 29, targetSdk 35), modelled on the proven woods-fork setup.
- applicationId `com.bridge.share`; package roots `conn` (SHAREit connection port),
  `channel` (SHAREit transfer port), `trigger` (NFC/BLE creds, from oshare-port), `ui`.
- AndroidManifest with Wi-Fi-Direct / BLE / NFC-HCE / foreground-service / media-read
  permissions. Minimal MainActivity placeholder.
- Debug keystore `keystore/debug.jks` (alias `bridge`).
- git repo initialised.
