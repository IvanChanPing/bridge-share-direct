# Bridge Share — Battery-Saving Design Spec

Modeled on the **proven oshare-port** on-demand host model. Authoritative source read
this session:

- `oshare-port/.../localsend/WoodsHostService.java` (the on-demand host lifecycle, port46)
- `oshare-port/.../localsend/CredsHceService.java` (NFC trigger / request-host APDU)
- `oshare-port/.../localsend/CredsBleServer.java` (creds advertiser/GATT server)
- `oshare-port/.../localsend/CredsBleClient.java` (sender-side creds scan)
- `shareit-bridge/.../ui/{ReceivePrefs,ReceiveService,BootReceiver}.java` (our current model)

## Core principle (the user's hard requirement)

> "Use the SHAREit method of only running it when needed... [running the hotspot 24/7
> is] extremely battery draining."

The Wi-Fi hotspot / Wi-Fi-Direct group is the expensive radio. It **must never run while
merely armed**. Armed = cheap BLE presence only. The expensive link comes up **only when a
sender actually initiates** (NFC tap or BLE wake) and is **torn down immediately after the
transfer or after a short watchdog timeout**.

oshare-port enforces exactly this split in `WoodsHostService`:

- `arm()` (ACTION_START): starts only the LocalSend HTTP server in listening state +
  `CredsHceService.armTrigger()` (HCE TRIGGER, no creds yet). It explicitly does **NOT**
  call `LohsBridge.start` and explicitly **defers the BLE creds advertiser** — see the
  comment "hotspot NOT up (on-demand)" and "BLE ready (advertiser deferred until on-demand
  hotspot bring-up)".
- `bringHotspotUp()` (ACTION_REQUEST_HOST): only fires from the sender's trigger, brings the
  hotspot up, **then** publishes creds to HCE + starts the BLE advertiser.
- `tearHotspotDown()` (ACTION_HOTSPOT_DOWN): stops the BLE creds advertiser + LOHS, returns
  to the cheap armed state (HCE re-armed to TRIGGER).
- `ACTION_EXPIRE`: AlarmManager `setExactAndAllowWhileIdle` fires the 10-min full teardown.

---

## 1. Receive-visibility states (radio behavior table)

Our three `ReceivePrefs.Mode` values map onto the radios as follows. The key insight:
**Off, Always-on, and Timed differ ONLY in whether/how long we hold the cheap BLE-presence
state. None of them keeps Wi-Fi hotspot up — that is always on-demand.**

| State | BLE advertise (presence) | BLE scan | Wi-Fi hotspot / group | HTTP server | Foreground service | Auto-expire alarm | Survives reboot |
|---|---|---|---|---|---|---|---|
| **OFF** | off | off | off | off | not running | none | no (BootReceiver no-ops) |
| **ALWAYS_ON** | **on, LOW_POWER mode** | off (only scans on send) | **DOWN until a sender triggers**, then up, then torn down | listening (cheap, loopback) | running, type CONNECTED_DEVICE | none (persistent) | **yes** |
| **TIMED (10 min)** | **on, LOW_POWER**, for 10 min only | off | **DOWN until triggered**, then up, then torn down | listening for 10 min | running for 10 min | `setExactAndAllowWhileIdle` @ +10 min | no (ephemeral) |

Notes:
- "BLE advertise" while armed = a tiny connectable advert carrying only the service UUID +
  short alias, so a sender can (a) discover us in its share-sheet radar and (b) GATT-connect
  to wake us. This is the cheap discoverability that lets the receiver be found **without
  holding Wi-Fi up**.
- ALWAYS_ON has **no timer** (matches oshare-port MODE_CONTACTS: "persistent — NO auto-stop").
- TIMED matches oshare-port MODE_EVERYONE: 10-min Doze-safe alarm → full teardown + reset
  the persisted mode to OFF.
- The HTTP server binding while armed is cheap (a listening socket, no radio). It only does
  real work once a peer has joined the on-demand Wi-Fi link.

---

## 2. On-demand host bring-up sequence (the exact oshare-port flow)

This is the proven trigger → bring-up → teardown sequence to replicate.

### Trigger → bring-up → teardown

1. **Arm (user sets Always-on or Timed).** `ReceiveService` starts as a foreground service.
   It arms cheap listeners ONLY:
   - start the BLE **presence advertiser** (LOW_POWER) so senders can discover + wake us;
   - arm the NFC HCE **TRIGGER** (`CredsHceService.armTrigger()` — empty/URI-only NDEF, no
     creds, because the hotspot SSID/PSK do not exist yet);
   - start the LocalSend/HTTP server in listening state.
   - **Do NOT bring up the hotspot. Do NOT advertise creds.** (battery)

2. **Sender initiates** — two equivalent triggers, both land at "request host on demand":
   - **NFC tap:** sender's reader sends the proprietary request-host APDU (`CLA=0x80,
     INS=0x10`) to our `CredsHceService`. While `sArmed`, the service calls
     `WoodsHostService.requestHostOnDemand(ctx)`. (HCE binds even when our app is fully
     backgrounded — that is what makes "receiver on any screen" work.)
   - **BLE wake:** (port57 refinement, recommended) the sender writes a "request-host"
     value to a dedicated GATT characteristic (`0000a1ef`) on our presence GATT server; the
     write handler calls the same `requestHostOnDemand(ctx)`.

3. **Bring-up (`bringHotspotUp`).** Idempotent (re-check `isActive()` so a tap that races the
   bring-up just re-publishes creds). Bring the Wi-Fi-Direct group / LocalOnlyHotspot up.
   On the async `onTetheringStarted`/group-created callback (`onHotspotUp`):
   - build `Creds{alias, fingerprint, ssid, psk, ip, port}`;
   - publish the **full** creds NDEF into HCE (`setCreds`) so an immediate re-tap carries
     them;
   - start the BLE **creds advertiser/GATT server** (`CredsBleServer.start(creds)`) — the
     sender that just triggered is scanning **right now** (`CredsBleClient`) and reads the
     creds characteristic.

4. **Sender joins + transfers.** Sender reads creds over BLE → joins the Wi-Fi-Direct group
   (no consent dialog: SSID starts with `DIRECT-` → `WifiP2pManager.connect` fast path; see
   the no-dialog-wifi memory) → uploads over HTTP to our LocalSend server.

5. **Teardown.** As soon as the transfer completes OR a watchdog fires (oshare-port port57
   used 90 s armed-without-join / 30 s post-join idle):
   - `tearHotspotDown()`: stop BLE creds advertiser, stop hotspot/group, re-arm HCE to
     TRIGGER. **Return to the cheap armed state** (presence advert still up if still within
     the visibility window).
   - The HTTP listening socket can stay; it costs nothing without a joined peer.

6. **Window expiry (Timed only).** The 10-min `AlarmManager` fires `ACTION_EXPIRE` →
   **full** teardown (server + BLE + hotspot all down), persisted mode reset to OFF,
   `stopForeground` + `stopSelf`. Re-arming before expiry resets the alarm.

### Why NFC carries only a trigger (not creds)

A LocalOnlyHotspot / Wi-Fi-Direct group's SSID/PSK **do not exist until the AP is started**,
so they cannot be baked into the NFC payload ahead of time. oshare-port solves this exactly
like real OnePlus Share: **NFC carries only a wake/trigger; the second radio (BLE) carries
the creds after on-demand bring-up.** Replicate this — do not try to put creds in the NFC tap.

---

## 3. BLE duty cycling (advertise vs scan vs idle)

| Role | When | What | Power |
|---|---|---|---|
| **Receiver, armed (Always-on/Timed)** | whole visibility window | BLE **advertise** presence (service UUID + alias), `ADVERTISE_MODE_LOW_POWER`, NOT connectable-for-data until triggered. Idle otherwise. | cheap (~mA) — this is the whole point |
| **Receiver, after trigger** | only during bring-up→transfer | BLE creds **GATT server + advertiser** (`CredsBleServer`, LOW_LATENCY ok briefly) so sender reads creds fast | brief, high, then off |
| **Sender, share-sheet open** | only while the picker is on screen | BLE **scan** (LOW_LATENCY) for receivers (presence + creds service UUID); stop scan on first match / on picker close | brief, high |
| **Sender, after creds read** | n/a | BLE off; Wi-Fi takes over | — |
| **Idle / OFF** | — | no BLE at all | zero |

Rules of thumb taken from the oshare-port code:
- **Receiver never scans while merely armed** — it only advertises. Scanning is the sender's
  job and only while its share sheet is open. This keeps the armed receiver cheap.
- Use **LOW_POWER** advertise mode for the long-lived presence advert; only the short-lived
  creds advertiser (post-trigger) uses LOW_LATENCY (`CredsBleServer` currently uses
  `ADVERTISE_MODE_LOW_LATENCY` + `TX_POWER_HIGH` — acceptable because it lives seconds).
- `setIncludeDeviceName(false)` on the advert (oshare-port does this) to keep the PDU small
  and avoid leaking the device name.
- The sender's `CredsBleClient` **stops the scan on first result** before connecting — never
  leave a scan running.

---

## 4. Foreground service, wake locks, alarms, teardown

### Foreground service
- One foreground service owns the armed lifecycle (our `ReceiveService`, mirroring
  `WoodsHostService`). Type **`FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`** (already set in
  `ReceiveService`; oshare-port uses the same `0x10` type). Keeps the process alive so the
  armed state survives the app being backgrounded — required for "set it once and leave".
- `IMPORTANCE_LOW` ongoing notification (no heads-up, no sound) — matches oshare-port.
- It is the FGS, **not** a persistent radio, that keeps us alive while armed. The radios stay
  cheap (BLE advert only).

### Wake locks
- **Acquire a partial `WakeLock` ONLY during an active transfer** (between
  `bringHotspotUp` → transfer complete), and release it in `tearHotspotDown`/on completion.
- **Never** hold a wake lock while merely armed — the FGS + BLE advert do not need the CPU
  awake; BLE advertising continues in Doze.
- Optionally a `WifiLock` (`WIFI_MODE_FULL_HIGH_PERF`) for the transfer duration only,
  released on teardown.

### Alarms / timers (the 10-min window)
- TIMED uses **`AlarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, ...)`** — it
  is Doze-safe, which a `Handler.postDelayed` is **not**. ⚠️ **Action item:** our current
  `ReceiveService` uses `handler.postDelayed` for the 10-min auto-stop — that will not fire
  reliably under Doze. Switch to the AlarmManager pattern from `WoodsHostService`
  (`expirePendingIntent()` → `PendingIntent.getForegroundService`, cancel-then-set on every
  re-arm so re-arming resets the timer).
- Use a **post-trigger watchdog** alarm too: if a trigger brought the hotspot up but no peer
  joined within ~90 s, auto-`tearHotspotDown` (don't leak the expensive radio). After a join,
  a shorter ~30 s idle watchdog tears down post-transfer.

### Teardown on completion / disconnect
- On transfer complete OR Wi-Fi-Direct/hotspot disconnect → `tearHotspotDown` immediately
  (stop creds advert, stop hotspot, release wake/wifi locks, re-arm HCE TRIGGER).
- On mode → OFF, on `ACTION_EXPIRE`, and in `onDestroy` → full teardown (everything down).

---

## 5. Concrete recommendations mapped onto ReceiveService / ReceivePrefs

`ReceivePrefs` is already correct in shape (OFF / ALWAYS_ON / TIMED, 10-min `TIMED_DURATION_MS`,
expiry stored, `getMode` downgrades expired TIMED→OFF, `BootReceiver` only re-arms ALWAYS_ON).
Keep it. Changes are all in `ReceiveService` (currently a lifecycle skeleton with TODOs):

1. **Replace `handler.postDelayed` auto-stop with an AlarmManager exact-and-allow-while-idle
   alarm** (Doze-safe), cancel-then-set on every start so re-arm resets the 10 min. Port the
   `expirePendingIntent()` / `scheduleExpireIfEveryone()` / `cancelExpireAlarm()` pattern
   from `WoodsHostService`. On expiry: `ReceivePrefs.setMode(OFF)` + full teardown +
   `stopForeground`/`stopSelf`. *(This is the one real bug in the current skeleton.)*

2. **Arm = cheap only.** In `onStartCommand` for ALWAYS_ON/TIMED, start (replacing the
   `TODO`): BLE presence advertiser (LOW_POWER), HCE TRIGGER arm, HTTP server listening.
   **Do NOT start the hotspot here.**

3. **Add an on-demand bring-up path.** Add intent actions analogous to oshare-port:
   `ACTION_REQUEST_HOST` (bring hotspot up + publish creds to HCE + start BLE creds
   advertiser), `ACTION_HOTSPOT_DOWN` (tear hotspot + creds advert down, stay armed),
   `ACTION_EXPIRE` (full teardown). Trigger sources: HCE service request-host APDU and the
   BLE GATT request-host write both call a static `requestHostOnDemand(ctx)` that fires
   `ACTION_REQUEST_HOST` (keep a static instance ref as a fallback, like
   `WoodsHostService.sInstance`).

4. **Wake lock discipline.** Acquire partial wake lock + optional WifiLock at
   `ACTION_REQUEST_HOST`, release in `ACTION_HOTSPOT_DOWN` / on transfer complete. Never hold
   while merely armed.

5. **Watchdogs.** Schedule a ~90 s post-trigger no-join watchdog and a ~30 s post-join idle
   watchdog (Handler is fine here since the device is awake mid-transfer) that fire
   `ACTION_HOTSPOT_DOWN`.

6. **Foreground type stays CONNECTED_DEVICE.** Already correct.

7. **Persistence already correct:** only ALWAYS_ON persists (BootReceiver), TIMED is
   ephemeral — matches oshare-port's "Everyone = ephemeral, Contacts = persistent" split.

### One deviation to flag (from oshare-port history)

The source-tree `WoodsHostService.java` (port46) deferred the BLE advert until **after** the
hotspot was up and used NFC→LOHS as the only trigger. The **shipped port57+** behavior (per
the realphone-round2 memory) moved to: **BLE-advertise-while-armed** (so the sender can
discover + BLE-wake us) + hotspot up only on the sender's **BLE GATT request-host write**
(char `0000a1ef`) + 90 s/30 s teardown watchdogs. This spec recommends the **port57+ model**
(BLE presence while armed is what makes us discoverable in the sender's radar without holding
Wi-Fi up), with NFC tap as an equivalent alternative trigger.
