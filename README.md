<div align="center">

<img src="docs/logo.svg" width="120" alt="iCSee Local Control logo" />

# iCSee Local Control

**Local-only Android control for iCSee / Xiongmai / XMEye cameras over their native DVRIP protocol — no cloud account, no cloud SDK, no analytics.**

[![CI](https://github.com/voidnullvalue/Icsee-android/actions/workflows/ci.yml/badge.svg)](https://github.com/voidnullvalue/Icsee-android/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/voidnullvalue/Icsee-android?sort=semver&color=22d3ee)](https://github.com/voidnullvalue/Icsee-android/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/voidnullvalue/Icsee-android/total?color=34d399&label=downloads)](https://github.com/voidnullvalue/Icsee-android/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white)
![Kotlin + Compose](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)

</div>

---

A greenfield Android app that talks **directly** to an iCSee/Xiongmai/XMEye-family
camera on your LAN over its native DVRIP protocol (TCP 34567) and RTSP — with
**no** iCSee/XMEye/JFTech cloud account, cloud SDK, Firebase, or analytics. Built
and verified against a real camera; every protocol decision is backed by captured
or live-probed bytes documented in [`PROTOCOL_NOTES.md`](PROTOCOL_NOTES.md).

## Contents

- [Download](#download)
- [Status](#status)
- [Features](#features)
- [Build from source](#build-from-source)
- [Architecture](#architecture)
- [Documentation](#documentation)
- [What's deliberately not here](#whats-deliberately-not-here)
- [License](#license)

## Download

Grab the latest APK from the [**Releases**](https://github.com/voidnullvalue/Icsee-android/releases/latest)
page and install it directly (enable *install unknown apps* for your browser or
file manager). Builds are produced by CI on every tagged release.

> Release builds are signed with the standard Android **debug** key — fine for
> sideloading, not for Play distribution.

## Status

A real, building, testable application. Verified live against the target camera:

| Capability | Status | Notes |
|---|---|---|
| DVRIP-Web login | ✅ Verified live | Plaintext login; password via the Sofia mod-62 MD5 hash. Real `SessionID`, `Ret: 100`. No RSA/AES needed on this path. |
| PTZ pan / tilt | ✅ Verified live | 8-direction movement and stop confirmed via `Ret` codes (and visible motion on-device). |
| Live video | ✅ Verified live | RTSP (H.265 + PCMA) via `androidx.media3`. DVRIP's own media channel claims OK but delivered no bytes on this camera, so RTSP is the real path. |
| Push-to-talk | ✅ Verified **audible** | Non-obvious: OPTalk *Claim* (1434) returns `Ret: 100` but leaves the speaker shut — a plaintext OPTalk **`Start`** (1430) opens it, then G.711 A-law frames play out loud. |
| BLE Wi-Fi provisioning | ✅ Verified on hardware | App scans, connects, sends Wi-Fi credentials over BLE, and the **camera joins the router**. It drops BLE before reporting its own login, so the app shows the factory `admin` / no-password. |
| Keepalive / reconnect | ✅ Verified live | `1006` keepalive `Ret: 100`; bounded-backoff reconnect. |
| LAN discovery | 🟡 Partial | Client probe frame byte-verified; beacon-response parsing implemented but not observed on this camera. |

Full evidence and the honest caveats live in [`PROTOCOL_STATUS.md`](PROTOCOL_STATUS.md).

## Features

- 📡 **LAN discovery** — UDP beacon probe + parsing, bounded window, multicast lock
- 🔐 **Confirmed plaintext DVRIP-Web login** — Sofia hash, verified end-to-end
- ♻️ **Session state machine** — keepalive + bounded-backoff reconnect
- 🎮 **PTZ** — 8-direction press-and-hold pad, stop-on-release, adjustable speed
- 🎥 **Live video** — RTSP (H.265 + PCMA) via `androidx.media3` / `PlayerView`, tap-to-fullscreen
- 🎙️ **Push-to-talk** — dedicated talk connection, OPTalk Claim + Start handshake, G.711 A-law upstream
- 📶 **BLE pairing / Wi-Fi provisioning** — scanner + GATT choreography matched to the factory app
- 🌙 **Dark, touch-first** Jetpack Compose UI
- 🩺 **Sanitized diagnostics** screen — never shows credentials or keys

## Build from source

```bash
./gradlew testDebugUnitTest assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK 36. CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml))
runs the unit tests, lint, and a debug assemble on every push and PR.

Developing on ARM64 (Termux/proot)? See [`BUILDING_IN_PROOT.md`](BUILDING_IN_PROOT.md)
for the aapt2-under-QEMU toolchain. Live-probe credentials go in the gitignored
`local-test.properties` (see `local-test.properties.example`).

## Architecture

```
app/src/main/kotlin/com/voidnullvalue/icseelocal/
  ui/          Compose screens + ViewModels (camera list, settings, live, diagnostics, BLE) + theme
  model/       CameraDescriptor, ConnectionState state machine
  discovery/   UDP beacon probe/parsing, multicast lock
  dvrip/       20-byte header framing, frame assembler, TCP transport, message-id catalog
  crypto/      Sofia password hash, RSA public-key parsing, AES SessionCrypto
  session/     Login negotiator, session manager, keepalive, reconnect backoff, command channel
  ptz/         OPPTZControl JSON builder, press-and-hold controller
  video/       RTSP player, media stream reassembly, codec probe, snapshot capture
  audio/       G.711 A-law codec, talk-frame wrapping, microphone capture, talk controller
  ble/         BLE scanner (manufacturer-data beacon match), pairing/Wi-Fi provisioning client + codec
  storage/     Keystore-backed credential storage, DataStore for non-sensitive prefs
```

## Documentation

| Doc | What's in it |
|---|---|
| [`PROTOCOL_NOTES.md`](PROTOCOL_NOTES.md) | The evidence — pcap + live findings, with exact bytes |
| [`PROTOCOL_STATUS.md`](PROTOCOL_STATUS.md) | Feature-by-feature verified/blocked status |
| [`BUILDING_IN_PROOT.md`](BUILDING_IN_PROOT.md) | ARM64 toolchain setup details |
| [`TESTING.md`](TESTING.md) | Unit tests + opt-in live hardware tests |
| [`SECURITY.md`](SECURITY.md) | Threat model, crypto/credential-storage choices |

## What's deliberately not here

No iCSee/XMEye/JFTech cloud SDK, no Firebase, no analytics/ads SDKs, no
Flutter/React Native/WebView wrapper, no custom crypto primitives, and no
hardcoded credentials.

## License

[MIT](LICENSE) © 2026 |VOID|

For use with cameras you own or are authorized to control on your own network.
DVRIP protocol details here were determined by observing traffic to/from a
camera under the author's control.
