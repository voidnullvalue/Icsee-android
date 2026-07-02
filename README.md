# iCSee Local Control

A greenfield Android app for direct, local (LAN-only) control of an
iCSee/Xiongmai/XMEye-family camera over its native DVRIP protocol on TCP
34567 — no iCSee/XMEye/JFTech cloud account, no cloud SDK, no analytics.

Built and developed against a real camera on the local network. See
`PROTOCOL_NOTES.md` for the exact evidence behind every protocol decision in
this codebase, and `PROTOCOL_STATUS.md` for what's verified versus still open.

## Status

This is a real, building, testable application. Verified live against the
target camera:

- **Login** — plaintext DVRIP-Web login (password via the Sofia mod-62 MD5
  hash), no RSA/AES negotiation needed for this login path. Real `SessionID`,
  `Ret: 100`.
- **PTZ** — 8-direction pan/tilt, movement and stop confirmed via `Ret` codes.
- **Live video** — via RTSP (H.265 + PCMA), rendered with `androidx.media3` /
  `PlayerView`. (DVRIP's own media channel claims successfully but never
  delivered media bytes on this camera, so RTSP is the real path.)
- **Push-to-talk** — two-way audio confirmed **audible** on the real camera
  speaker. The key non-obvious step: the OPTalk *Claim* (1434) returns
  `Ret: 100` but leaves the speaker closed; a plaintext OPTalk **`Start`**
  (1430) on the control connection is what actually opens it, after which
  streamed G.711 A-law frames play out loud.

## Features

- LAN discovery (UDP beacon probe + parsing, bounded window, multicast lock)
- Manual camera configuration
- DVRIP framing (20-byte header, JSON and encrypted payload handling)
- Confirmed-working plaintext DVRIP-Web login, verified live end-to-end
- Authenticated session state machine with keepalive and bounded-backoff reconnect
- PTZ: 8-direction press-and-hold pad with stop-on-release, adjustable speed
- Live video: RTSP (H.265 + PCMA), rendered via `androidx.media3` / `PlayerView`, tap-to-fullscreen
- Push-to-talk: separate talk connection, OPTalk Claim + Start handshake, G.711 A-law upstream audio
- BLE pairing / Wi-Fi provisioning research path (unit-tested codec)
- Dark, touch-first Jetpack Compose UI
- Sanitized protocol diagnostics screen (no credentials/keys ever shown)

## Quick start

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

See `BUILDING_IN_PROOT.md` for how the ARM64 proot/Termux build toolchain was
set up (in particular the aapt2 situation — there is no native ARM64 aapt2 new
enough for this project's compileSdk, so the official x86_64 build runs under
QEMU user-mode emulation).

Test credentials for live probing go in `local-test.properties` (gitignored);
see `local-test.properties.example`.

## Project layout

```
app/src/main/kotlin/com/voidnullvalue/icseelocal/
  ui/           Compose screens + ViewModels (camera list, settings, live control, diagnostics, BLE) + theme
  model/        CameraDescriptor, ConnectionState state machine
  discovery/    UDP beacon probe/parsing, multicast lock
  dvrip/        20-byte header framing, frame assembler, TCP transport, message id catalog
  crypto/       Sofia password hash, RSA public key parsing, AES SessionCrypto
  session/      Login negotiator, session manager, keepalive, reconnect backoff, command channel
  ptz/          OPPTZControl JSON builder, press-and-hold controller
  video/        RTSP player, media stream reassembly, codec probe, snapshot capture
  audio/        G.711 A-law codec, talk frame wrapping, microphone capture, talk controller
  ble/          BLE Wi-Fi provisioning codec
  storage/      Keystore-backed credential storage, DataStore for non-sensitive prefs
```

## Documentation

- `PROTOCOL_NOTES.md` — the evidence: what was found in the pcap and in live
  probing, with exact bytes.
- `PROTOCOL_STATUS.md` — feature-by-feature status.
- `BUILDING_IN_PROOT.md` — ARM64 toolchain setup details.
- `TESTING.md` — how to run unit tests and opt-in live hardware tests.
- `SECURITY.md` — threat model, crypto/credential storage choices.

## What's deliberately not here

No iCSee/XMEye/JFTech cloud SDK, no Firebase, no analytics/ads SDKs, no
Flutter/React Native/WebView wrapper, no custom crypto primitives, no
hardcoded credentials.

## Disclaimer

For use with cameras you own or are authorized to control on your own network.
DVRIP protocol details here were determined by observing traffic to/from a
camera under the author's control.
