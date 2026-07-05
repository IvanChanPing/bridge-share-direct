# Bridge Share Direct

The original **Bridge Share** — direct **Android-to-Android** file transfer. A SHAREit-Lite
**Wi-Fi-Direct** port, triggered by an NFC/BLE tap. No iPhone, OnePlus, or cloud dependencies —
just two Android phones and a fast local transfer.

## ⬇️ Download now

Install **both** APKs on each phone:

### [**⬇️ Download Bridge Share Direct (the app)**](https://github.com/IvanChanPing/bridge-share-direct/releases/latest/download/bridge-share-direct-debug.apk)

### [**⬇️ Download Radio Helper (companion)**](https://github.com/IvanChanPing/bridge-share-direct/releases/latest/download/radio-helper-debug.apk)

Both links always point at the latest release.

## What each APK is

- **Bridge Share Direct** (`com.bridge.share.directapp`) — the app you use to send and receive files.
- **Radio Helper** (`dev.superdrop.radiohelper`) — a small optional companion that silently turns
  Wi-Fi/Bluetooth on at the start of a transfer and restores them after, and can grant the
  "draw over other apps" (overlay) permission in one tap. Bridge Share Direct works without it and
  falls back to the normal system toggles; the helper just makes those steps automatic.

Both APKs are signed with the same key, which is required for the app to talk to the helper.

## Install

1. Download both APKs above onto each phone.
2. Install them (allow "install unknown apps" for your browser/file manager if prompted).
3. Open **Bridge Share Direct**, choose a receiving mode (Off / Always on / On for 10 minutes),
   and share files from any app's share sheet or by tapping two phones together.

## Build from source

Two independent Gradle projects (the app at the repo root, the helper under `radio-helper/`), each
with its own wrapper:

```bash
# App (Bridge Share Direct)
./gradlew assembleDirectDebug

# Radio Helper
cd radio-helper && ./gradlew assembleDebug
```

Releases are produced automatically by `.github/workflows/release-apk.yml`: push a tag (e.g. `v1.0`)
and CI builds both APKs and attaches them to a GitHub Release.

## Flavors

The app ships three engine flavors — `direct` (Wi-Fi Direct, primary), `aware` (Wi-Fi Aware), and
`hotspot`. The published build is `direct`.
