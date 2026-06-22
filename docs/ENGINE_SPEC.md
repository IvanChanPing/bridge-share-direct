# Bridge Share — Engine Architecture Spec: 3 Interchangeable Connection Variants

Status: design spec. Grounds every claim in the actual code under
`src/main/java/com/bridge/share/` (read in full 2026-05-29), the Wi-Fi-Direct
tutorial (`wifi-direct-tutorial.md`), and the silent-join memory
(`reference_silent_wifi_join_constraint_2026_05_29.md`).

Goal: two phones, both running our app, exchange files over a phone-to-phone
Wi-Fi data path with as little user friction as possible. The link layer must be
**pluggable** behind one interface so the engine can pick — or fall back across —
three variants:

1. **Wi-Fi Direct connect-by-config** — already coded
   (`WiDiNetworkManager` host `createGroup` + `WifiP2pConnector` client `connect`,
   both with `WifiP2pConfig.Builder().setNetworkName().setPassphrase()`,
   `enablePersistentMode(false)`, **no `WpsInfo.PBC`**). Zero peer dialog on
   API 29+ (per tutorial §2.2). The reference implementation of this spec.
2. **Wi-Fi Aware (NAN)** — `WifiAwareManager.attach` → publish/subscribe by
   serviceName → `WifiAwareNetworkSpecifier` + `ConnectivityManager.requestNetwork`
   → peer IPv6 + port from `WifiAwareNetworkInfo` → transfer over
   `network.getSocketFactory()`. Zero prompts, no privilege; **hard-gated on
   `PackageManager.FEATURE_WIFI_AWARE` hardware**.
3. **Nearby + LocalOnlyHotspot + WifiNetworkSpecifier** — host
   `WifiManager.startLocalOnlyHotspot` (system SSID `AndroidShare_xxxx` + random
   WPA2), joiner `WifiNetworkSpecifier` + `requestNetwork`. **One "Connect" tap on
   the joiner** is the floor for a non-privileged app (per memory). Works
   without Wi-Fi Aware hardware; the universal fallback.

The HTTP transfer layer (`ShareChannel` / `ShareHttpServer` / `DownloadClient`)
and the trigger layer (`Creds` / BLE / NFC HCE) are **variant-agnostic** and reused
verbatim across all three.

---

## 1. The `Connection` interface

A single Java interface that all three variants implement. It replaces the
two-callback split currently in `BridgeConnection` (`HostCallback` +
`JoinCallback`) with one symmetric contract that carries an abstract
**`ConnDescriptor`** instead of raw `ssid/psk`, so Aware (which has no SSID/PSK)
fits the same shape.

```java
package com.bridge.share.conn;

import android.content.Context;
import java.net.Network;            // null for Direct/hotspot (default network)
import javax.net.SocketFactory;     // bound factory for Aware; null otherwise

/**
 * One pluggable link-layer. Implemented by WifiDirectConnection,
 * WifiAwareConnection, HotspotConnection. The engine talks ONLY to this; it
 * never references WifiP2pManager / WifiAwareManager / WifiManager directly.
 *
 * Lifecycle (host):   startHost(body) -> onCredsReady(desc) -> [peer joins] -> onConnected(ep)
 * Lifecycle (joiner): join(desc)      -> onConnected(ep)
 * Both ends call stop() to tear down. All callbacks are posted to the main thread.
 */
public interface Connection {

    /** Which of the 3 variants this is. */
    Variant variant();

    enum Variant { WIFI_DIRECT, WIFI_AWARE, HOTSPOT }

    /** Is this variant usable on THIS device right now (feature + radio)? */
    boolean isSupported(Context ctx);

    /**
     * HOST: bring up the link as the server side and publish a descriptor the
     * joiner needs out-of-band (over BLE/NFC). `body` is the identity body
     * embedded in the SSID/serviceName (see SsidHelper.buildDirectNetworkName /
     * trimToBytes). Non-blocking; result via Listener.
     */
    void startHost(String body, Listener cb);

    /**
     * JOINER: connect using a descriptor received out-of-band (BLE/NFC).
     * For Aware the descriptor carries serviceName + passphrase (no ip/port at
     * join time — those arrive in onConnected via WifiAwareNetworkInfo).
     * Non-blocking; result via Listener.
     */
    void join(ConnDescriptor desc, Listener cb);

    /** Idempotent teardown: remove group / unregister network / stop hotspot. */
    void stop();

    /** All methods invoked on the main thread. */
    interface Listener {
        /** HOST only: descriptor is ready to hand to the joiner (BLE/NFC). */
        void onCredsReady(ConnDescriptor desc);
        /** BOTH: link is up; endpoint tells the transfer layer how to reach the peer. */
        void onConnected(Endpoint ep);
        /** Terminal failure for this attempt. */
        void onFailed(String reason);
        void onLog(String msg);
    }
}
```

### 1.1 `ConnDescriptor` — the variant-agnostic creds/handshake payload

Generalises today's `Creds` (which is hard-wired to `ssid/psk/hostIp/port`). It is
serialised to/from JSON and carried over BLE GATT read or NFC NDEF external record
**unchanged from the existing trigger code** — only the field set grows and a
`variant` discriminator is added so the joiner knows which `Connection` to
instantiate.

```java
public final class ConnDescriptor {
    public Connection.Variant variant; // WHICH link the joiner must build
    public String alias;               // human name (unchanged)

    // Wi-Fi Direct + Hotspot use these (Aware leaves them null):
    public String ssid;                // Direct: DIRECT- networkName; Hotspot: AndroidShare_xxxx
    public String psk;                 // WPA2 passphrase
    public String hostIp;              // Direct: 192.168.49.1; Hotspot: reservation gateway ip

    // Wi-Fi Aware uses these (Direct/Hotspot leave serviceName null):
    public String serviceName;         // Aware publish/subscribe service name
    public byte[] matchFilter;         // optional Aware discovery hint (e.g. session nonce)
    public String pskPassphrase;       // Aware setPskPassphrase (>=8 chars)

    // Transfer layer (all variants):
    public int port = ShareHttpServer.DEFAULT_PORT; // 2999; Aware ALSO carries it via setPort
}
```

`Creds` is kept as a thin alias/compat shim (its existing JSON keys map onto the
Direct/Hotspot fields) so `BridgeEngine` and the BLE/NFC classes need only swap the
type name. Aware adds the `serviceName`/`pskPassphrase` keys.

### 1.2 `Endpoint` — how the transfer layer reaches the peer

The single abstraction that lets `ShareChannel` plug into all three. Resolves the
"where do I bind / what host do I GET" question per variant.

```java
public final class Endpoint {
    public String host;           // IPv4 for Direct(192.168.49.1)/Hotspot; IPv6 literal for Aware
    public int port;              // 2999
    public Network network;       // Aware: the granted Network; Direct/Hotspot: null (default route)
    public SocketFactory socketFactory; // Aware: network.getSocketFactory(); else null
    public boolean isIpv6;        // true for Aware (must bracket the host in URLs)
}
```

---

## 2. The three variant implementations (one-liners + per-variant detail)

| Variant | `startHost` (server) | `join` (client) | Endpoint |
|---|---|---|---|
| **WifiDirectConnection** | `WifiP2pConfig.Builder().setNetworkName(DIRECT-…).setPassphrase(md5/"12345678").enablePersistentMode(false)` → `manager.createGroup`; GO IP `192.168.49.1` (existing `WiDiNetworkManager`). | matching `WifiP2pConfig` → `manager.connect`; GO IP via `requestConnectionInfo` (existing `WifiP2pConnector`). | IPv4 `192.168.49.1`, default network, no socket factory. |
| **WifiAwareConnection** | `attach` → `publish(PublishConfig serviceName)`; open `ServerSocket(0)`; `WifiAwareNetworkSpecifier.Builder(pubSession,peerHandle).setPskPassphrase().setPort(p)` → `requestNetwork`. | `attach` → `subscribe(serviceName)` → `onServiceDiscovered(peerHandle)` → sendMessage to reveal self → `WifiAwareNetworkSpecifier` (no port) → `requestNetwork` → `WifiAwareNetworkInfo.getPeerIpv6Addr()+getPort()`. | IPv6 + port from `WifiAwareNetworkInfo`, the granted `Network`, `network.getSocketFactory()`. |
| **HotspotConnection** | `WifiManager.startLocalOnlyHotspot` → reservation `WifiConfiguration`/`SoftApConfiguration` SSID `AndroidShare_xxxx` + random WPA2; gateway IP. | `WifiNetworkSpecifier.Builder().setSsid().setWpa2Passphrase()` → `requestNetwork` (**ONE system "Connect?" tap**) → bind to granted `Network`. | IPv4 gateway, granted `Network` (bind socket to it), no socket factory. |

### 2.1 WifiDirectConnection (variant 1 — reference, already coded)
Wraps the existing `WiDiNetworkManager` (host) and `WifiP2pConnector` (client)
behind `Connection`. `startHost` → `WiDiNetworkManager.start`; on `onGroupReady`
build a `ConnDescriptor{variant=WIFI_DIRECT, ssid=networkName, psk=passphrase,
hostIp=192.168.49.1, port=2999}` and fire `onCredsReady` + `onConnected`. `join` →
`WifiP2pConnector.connect(desc.ssid, desc.psk)`; on `onConnected(go)` emit
`Endpoint{host=go, network=null}`. Threading/timeouts already handled inside those
classes (broadcast receiver on main looper, 30 s `CONNECT_TIMEOUT_MS`).
Per tutorial §6: GO = HTTP server, client = HTTP pull.

### 2.2 WifiAwareConnection (variant 2 — new)
No SSID/PSK exist; discovery is intrinsic to Aware. Sequence (per memory + Aware
docs):
- Both: `ctx.getSystemService(WifiAwareManager)`; `attach(AttachCallback)` →
  `WifiAwareSession`.
- **Host = publisher**: `session.publish(new PublishConfig.Builder()
  .setServiceName(desc.serviceName).build(), cb)`; in `onPublishStarted` keep the
  `PublishDiscoverySession`. Open `ServerSocket(0)`, read the assigned `port`.
  On the peer's first message (`onMessageReceived(peerHandle,msg)`) capture the
  `PeerHandle`, then `WifiAwareNetworkSpecifier.Builder(pubSession, peerHandle)
  .setPskPassphrase(desc.pskPassphrase).setPort(port)` → `cm.requestNetwork(req,
  callback)`. `onAvailable(network)` → `onConnected(Endpoint{network,
  socketFactory=network.getSocketFactory(), host=<own ipv6>, port})` and start
  the HTTP server bound to that port.
- **Joiner = subscriber**: `session.subscribe(SubscribeConfig.setServiceName)`;
  `onServiceDiscovered(peerHandle, …)` → `subSession.sendMessage(peerHandle, id,
  bytes)` to reveal itself → `WifiAwareNetworkSpecifier.Builder(subSession,
  peerHandle).setPskPassphrase(desc.pskPassphrase)` (NO port) → `requestNetwork`.
  In `onCapabilitiesChanged(net, caps)` read
  `((WifiAwareNetworkInfo)caps.getTransportInfo()).getPeerIpv6Addr()` +
  `.getPort()` → `onConnected(Endpoint{host=ipv6, port, network=net,
  socketFactory=net.getSocketFactory(), isIpv6=true})`.
- Android 12+: responder accepts any peer (no MAC). **No dialog at any step.**
- `isSupported` = `pm.hasSystemFeature(FEATURE_WIFI_AWARE) &&
  awareManager.isAvailable()`.

### 2.3 HotspotConnection (variant 3 — new, the universal fallback)
- **Host**: `wifiManager.startLocalOnlyHotspot(LocalOnlyHotspotCallback,
  handler)`; in `onStarted(reservation)` read SSID + passphrase
  (`reservation.getWifiConfiguration()` / `getSoftApConfiguration()` on 30+) and
  the gateway IP; emit `ConnDescriptor{variant=HOTSPOT, ssid, psk, hostIp, port}`;
  start the HTTP server. Keep the reservation alive until `stop()`
  (`reservation.close()`).
- **Joiner**: `new WifiNetworkSpecifier.Builder().setSsid(desc.ssid)
  .setWpa2Passphrase(desc.psk).build()` → `NetworkRequest` with
  `addTransportType(TRANSPORT_WIFI)` + `removeCapability(NET_CAPABILITY_INTERNET)`
  → `cm.requestNetwork(req, cb)`. **System shows one "Connect to <SSID>?" dialog →
  user taps once.** `onAvailable(network)` → `cm.bindProcessToNetwork(network)` (or
  pass the `Network` into `Endpoint` so the socket factory binds) →
  `onConnected(Endpoint{host=desc.hostIp, port, network})`.
- `isSupported` = `wifiManager.isWifiEnabled()` (LOHS needs Wi-Fi on); always true
  enough to be the fallback when Aware/Direct are absent or fail.

### 2.4 Variant selection / fallback
`ConnectionFactory.pick(ctx, prefer)` returns the first supported variant in
order: **Aware (zero-tap, if `FEATURE_WIFI_AWARE`) → Wi-Fi Direct (zero-tap) →
Hotspot (one tap)**. The host stamps the chosen `variant` into the
`ConnDescriptor`, so the joiner deterministically instantiates the matching
`Connection` — no negotiation needed.

---

## 3. How the BLE / NFC trigger drives it

The trigger layer is unchanged in mechanism (`CredsBleServer` GATT read,
`CredsBleClient` scan+read, `CredsHceService` HCE NDEF, `CredsNfcReader` reader);
only the payload type widens from `Creds` to `ConnDescriptor` (same JSON
transport, same UUIDs/AID).

**Host publish:**
1. Engine `pick`s a variant, calls `connection.startHost(body)`.
2. `onCredsReady(desc)` → `desc.toBytes()` is advertised two ways (exactly as
   `BridgeEngine.startHostShare` does today):
   - BLE: `CredsBleServer.start(desc)` advertises `SERVICE_UUID 0000a1ec-…`,
     serves the JSON on `CREDS_CHAR_UUID 0000a1ed-…` (MTU-negotiated read).
   - NFC HCE: `CredsHceService.setCreds(desc.toBytes())` (AID `F04C53574F4F4453`,
     Type-4 NDEF external record `bridge.share:creds`).

**Joiner read → join:**
1. `CredsBleClient` (scan `SERVICE_UUID` → connect → read char) **or**
   `CredsNfcReader` (tap → `SELECT AID` → `REQUEST_HOST` → read NDEF) returns the
   bytes → `ConnDescriptor.fromBytes`.
2. Engine reads `desc.variant`, instantiates the matching `Connection`, calls
   `connection.join(desc)`.

**What the BLE/NFC payload carries per variant** (the exact handshake — §6):
- **Wi-Fi Direct / Hotspot**: `ssid`, `psk`, `hostIp`, `port` (today's `Creds`).
- **Wi-Fi Aware**: Aware self-discovers, so the payload carries **`serviceName`**
  (the publish/subscribe rendezvous string) + **`pskPassphrase`** (link
  encryption) + `port` as a hint. No SSID/IP — the joiner gets the peer's IPv6
  and port from `WifiAwareNetworkInfo` at connect time, not from the trigger.
  The trigger here is purely the *initiate gesture* + serviceName agreement;
  Aware could even derive a fixed serviceName from a shared app secret and skip
  BLE entirely, but carrying it keeps multiple concurrent sessions disjoint.

`CredsHceService.armTrigger()` / the `HostTrigger` hook keeps the existing
on-demand "tap with no creds yet → start hosting → then advertise" path
(`CredsNfcReader.onTriggerSentScanBle` → `joinViaBle`) for all variants.

---

## 4. How HTTP transfer plugs in per variant

`ShareChannel` / `ShareHttpServer` / `DownloadClient` are reused unchanged in
logic. The only addition is that the **client** must honour the `Endpoint`
(network binding + IPv6 bracketing). Two small touch-points:

- **Server side (`ShareHttpServer`)**: binds `new ServerSocket(port)`.
  - Direct/Hotspot: binds on the default interface; reachable at `192.168.49.1` /
    hotspot gateway. No change.
  - Aware: bind the `ServerSocket` to the Aware interface and pass its bound port
    into the `WifiAwareNetworkSpecifier.setPort(port)` so the peer learns it via
    `WifiAwareNetworkInfo.getPort()`. (Aware's `ServerSocket(0)` chooses the port;
    `DEFAULT_PORT 2999` is only the Direct/Hotspot default.)
- **Client side (`DownloadClient`)**: currently builds
  `new URL("http://" + host + ":" + port + …)` and `url.openConnection()`. Two
  changes driven by `Endpoint`:
  1. **IPv6** (Aware): wrap the host in brackets → `http://[<ipv6>]:<port>/…`
     when `endpoint.isIpv6`.
  2. **Network binding** (Aware + Hotspot): route the request through the granted
     `Network`. Either `endpoint.network.openConnection(url)` instead of
     `url.openConnection()`, **or** for Aware use
     `endpoint.socketFactory.createSocket(ipv6, port)` and drive HTTP over that
     socket. Direct uses the default route (`network == null`) → no change.

`ShareChannel.startClient(...)` gains an `Endpoint` parameter; everything below
it (manifest `/msg/collection`, `/download?recordid&metadataid&filetype&position`,
64 KB streaming, byte-position resume) is identical across variants.

---

## 5. Threading / Service model, timeouts, teardown, error handling

**Service model.** A single foreground service (`ReceiveService`, type
`connectedDevice` — already declared) owns one live `Connection` + one
`ShareChannel` + the trigger publishers for the host role. Its existing TODOs
("start BLE advertise + host (hotspot/aware) + HTTP server here" / "stop … here")
are the exact insertion points: `onStartCommand` → `BridgeEngine.startHostShare`,
`onDestroy` → `BridgeEngine.stop`. TIMED mode (10-min auto-stop) and boot re-arm
are unchanged.

**Threading.**
- All framework callbacks (`WifiP2pManager` broadcasts, `WifiAwareManager`
  attach/discovery/network callbacks, `LocalOnlyHotspotCallback`,
  `ConnectivityManager.NetworkCallback`, BLE GATT callbacks) fire on the **main
  looper** — `Connection` implementations marshal results to `Listener` on the
  main thread (matches `WiDiNetworkManager` using `appCtx.getMainLooper()`).
- All socket I/O runs **off the main thread**: server on
  `ShareHttpServer`'s cached thread pool + daemon accept thread; client on the
  daemon `"ShareChannel-client"` thread (existing). `BridgeEngine.Events`
  callbacks are emitted from those worker threads — the UI layer must re-post to
  the main thread (it already does in `MainActivity`).

**Timeouts.**
- Direct connect: 30 s (`WifiP2pConnector.CONNECT_TIMEOUT_MS`, existing).
- Aware: a connect watchdog (same 30 s) around `requestNetwork` → `onAvailable`;
  `onUnavailable` is a hard fail.
- Hotspot: watchdog around `requestNetwork`; user may never tap → fail after
  ~60 s (the system dialog can sit open) and surface "join not confirmed".
- HTTP: `ShareHttpServer` SO timeout 300 s; `DownloadClient` connect 15 s /
  read 300 s (existing).

**Teardown.** `Connection.stop()` is idempotent and variant-specific:
- Direct: `removeGroup` + unregister receiver + `channel.close()` (existing).
- Aware: `cm.unregisterNetworkCallback`, `discoverySession.close()`,
  `awareSession.close()`, close the `ServerSocket`.
- Hotspot: `reservation.close()` (drops the LOHS) +
  `cm.unregisterNetworkCallback` + `cm.bindProcessToNetwork(null)`.
`BridgeEngine.stop()` already cascades: `bleServer/bleClient.stop()`,
`CredsHceService.clear()`, `channel.stop()`, `connection.stop()`.

**Error handling.** Every variant routes failures through `Listener.onFailed` →
`Events.onError`. Reason strings: Direct maps `WifiP2pManager` codes
(`WiDiNetworkManager.reasonStr`); Aware maps attach/publish/subscribe failure +
`onUnavailable`; Hotspot maps `LocalOnlyHotspotCallback.onFailed(reason)` +
`requestNetwork` `onUnavailable`. `isSupported` gates each variant before use so
"no Aware hardware" is a clean skip-to-fallback, not a runtime crash.

---

## 6. Exact creds/handshake payload per variant (JSON over BLE/NFC)

All sent as the same `ConnDescriptor` JSON the trigger layer already transports.

**Variant 1 — Wi-Fi Direct (today's `Creds`, + discriminator):**
```json
{ "variant":"WIFI_DIRECT", "alias":"Pixel-7",
  "ssid":"DIRECT-si-<body>", "psk":"<md5hex(name)[:8] | 12345678>",
  "host_ip":"192.168.49.1", "port":2999 }
```

**Variant 2 — Wi-Fi Aware (no SSID/IP; serviceName + passphrase):**
```json
{ "variant":"WIFI_AWARE", "alias":"Pixel-7",
  "service_name":"com.bridge.share.v1", "psk_passphrase":"<>=8 chars>",
  "match_filter":"<base64 nonce, optional>", "port":2999 }
```
(IPv6 + actual port are NOT in the payload — joiner reads them from
`WifiAwareNetworkInfo` at connect time.)

**Variant 3 — LocalOnlyHotspot (same shape as Direct, different SSID family):**
```json
{ "variant":"HOTSPOT", "alias":"Pixel-7",
  "ssid":"AndroidShare_1234", "psk":"<random WPA2 from reservation>",
  "host_ip":"<lohs gateway ip>", "port":2999 }
```

The `variant` field is the single switch the joiner reads first to pick which
`Connection` to build; every other field is interpreted per that variant.
