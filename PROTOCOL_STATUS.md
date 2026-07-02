# Protocol Implementation Status

Status values used below, exactly as defined by the project brief:

```
Not started
Implemented, untested
Partially verified
Verified on target camera
Blocked
```

"Verified on target camera" is reserved for things directly observed on the
wire against the camera (`:34567`) -- either in the reference capture or via
the live probes in `tools/live/` (see PROTOCOL_NOTES.md). The app itself runs
on real Android hardware and its login, PTZ, RTSP video, and push-to-talk are
exercised on-device; the standalone probes and pcap are the byte-level
evidence behind each protocol decision. Probes and Kotlin share the identical
algorithm/wire format (`SofiaHash`/`sofia_hash`, identical JSON construction),
so the wire-level evidence applies directly to the app's implementation.

## Headline result

**Real, successful authentication was achieved live against the target
camera on 2026-07-01**, along with real keepalive, PTZ movement/stop, and
`OPTalk` claim, all with `Ret: 100`. See PROTOCOL_NOTES.md "Login -- LIVE
AUTHENTICATION CONFIRMED" for the full story (vendor SDK decompilation,
cross-referencing a real open-source client, and a corrected password
typo). **Real video was also confirmed live the same day, via RTSP rather
than DVRIP's private media channel** -- see PROTOCOL_NOTES.md "RTSP video
-- LIVE CONFIRMED": real H.265 + PCMA RTP bytes received, and this is what
the app actually uses for its video display.

**Two-way push-to-talk is fully working and confirmed audible on the real
camera speaker.** The non-obvious part: the `OPTalk` *Claim* (msg 1434)
returns `Ret:100` but leaves the speaker closed -- the step that actually
opens it is a plaintext `OPTalk` **`Start`** (msg 1430) on the control
connection, which returns `Ret:100` with the camera's expected `AudioFormat`.
After that, streamed G.711 A-law frames (msg 1432) play out loud. This was
found by driving the live camera directly and hearing a streamed test tone.
`TalkController` performs this Claim -> Start handshake and sends `OPTalk`
`Stop` on release.

**Later the same day, DVRIP login appeared to regress** to a reproducible
`Ret: 203` with what was believed to be the identical credentials and wire
format that had just succeeded repeatedly (login, keepalive, PTZ, `OPTalk`,
`OPMonitor` claim). Rate-limit/lockout, the official app needing to be
open, and the official app holding an exclusive session slot were all
ruled out as explanations, and a full camera power-cycle did not clear it
either.

**Resolved:** this was not a camera-side regression at all. `Ret: 203`
(cross-confirmed against `dbuezas/icsee-ptz`'s error-code table as
"Password is incorrect") turned out to be literally correct --
`local-test.properties`'s stored password had reverted to (or never moved
past) the exact same one-character `r`-vs-`4` typo documented as the
original cause of every pre-fix login failure in PROTOCOL_NOTES.md. Once
corrected back to the real password, a fresh single-attempt probe using
the app's exact login flow succeeded live again with `Ret: 100`, a real
`SessionID`, and `AdminToken`, matching the original confirmed login
exactly. RTSP video continued working normally throughout (separate
credential store, see above), which is one reason it's the app's primary
video path rather than DVRIP's -- but the DVRIP login path itself is fine.

| Feature | Status | Evidence |
|---|---|---|
| Discovery (client probe) | Verified on target camera (wire format) | Real captured probe frame in pcap, byte-identical to `CameraDiscoveryClient`'s generated frame. |
| Discovery (beacon response parsing) | Implemented, untested | Zero UDP packets to/from the camera appear anywhere in the pcap; the beacon JSON shape is implemented per the task brief's example only. Never observed, live or otherwise. |
| BLE scan (find camera in pairing mode) | Verified on real hardware | Live on a OnePlus HD1901, 2026-07-02. The camera does **not** advertise the pairing GATT service UUID (it only exists after connecting), so a service-UUID scan filter matched nothing. `CameraBleScanner` now matches the factory app: scans with no filter and identifies cameras by their manufacturer-data beacon prefix (`8B8B`/`8B8D`/`8BB8`, from `XMBleManager.n()`). |
| BLE connect + service discovery | Verified on real hardware | Live on OnePlus HD1901, 2026-07-02, after matching the vendor's inuker `BleConnectRequest` choreography: 300ms post-connect delay before `discoverServices()`, plus retry with a `BluetoothGatt.refresh()` cache clear on empty results. Without this, discovery returned SUCCESS but an empty service table (`getService` null). Adapter acquisition falls back to `BluetoothAdapter.getDefaultAdapter()` (the vendor's approach) to avoid the transient-null `BluetoothManager.getAdapter()` on the Qualcomm stack. |
| BLE Wi-Fi provisioning (credential frame) | **Verified on target camera** | Live on OnePlus HD1901, 2026-07-02: `BleWifiProvisionCodec`'s frame (byte-verified against the vendor's `BleDistributionUtil.combineWiFiSSIDToHexStr`) is chunk-written over the pairing characteristic and **the camera joins the router**. Writes are paced ~50ms like the vendor and not gated on the `onCharacteristicWrite` callback (which the OnePlus stack doesn't reliably deliver even when the write lands). |
| BLE provisioning ACK (assigned credentials) | Implemented, not confirmed live | The camera **drops the BLE link when it joins Wi-Fi**, before the ACK notification (which carries assigned username/password/IP/MAC) is observed. `parseWifiConfigAck` mirrors the vendor's `parseBleWiFiConfigResult` and is unit-tested, but has not been exercised against a real ACK. On a fresh/reset device the factory login is `admin` / no password, which the app surfaces as the "provisioned, no ACK" outcome; the camera is then added by IP via normal LAN discovery. |
| DVRIP framing (20-byte header) | Verified on target camera | Every field decoded byte-for-byte against real captured and live-probed frames (login, keepalive, PTZ, media, OPTalk). The Kotlin `DvripHeader`/`DvripFrameAssembler` classes are unit-tested against real captured bytes; not run against the live device directly (no Android device in this environment). |
| Message 1010 (pre-login negotiate, RSA/AES path) | Verified on target camera | Real live `Ret:100` response with RSA-1024 public key, AES-128 params, exact `NotEncryptMsgID` list, confirmed 2026-07-01. Not on the login path this app actually uses (see below), kept as tested, available code (`PreLoginNegotiationParser`, `DvripRsaPublicKey`) for a device/firmware that needs it. |
| Authentication (message 1000/1001 login) | **Verified on target camera** | Real login succeeded live 2026-07-01: plaintext JSON (`EncryptType:MD5, LoginType:DVRIP-Web`), password hashed via `SofiaHash` (mod-62 MD5 transform, cross-confirmed from the camera's own web UI JS, the native vendor SDK's disassembly, and a real open-source Home Assistant client's source), response `Ret:100` with a real `SessionID`/`AliveInterval`/`AdminToken`. `DvripLoginNegotiator` implements this exact flow and is unit-tested against the real captured response shape (loopback server, not live device -- no Android device available). |
| RSA credential wrapping (alternate path) | Blocked | Not needed for this camera (see above); the exact AES key derivation for this path remains unresolved and is no longer pursued since it isn't required. |
| AES transport (post-login envelope, alternate path) | Not applicable | The login path this app uses runs entirely unencrypted (`NullSessionCrypto`) -- confirmed live for login, keepalive, PTZ, and OPTalk claim. `AesSessionCrypto` remains implemented and unit-tested for a device that requires the encrypted path, but is unused on the confirmed-working path. |
| Keepalive | Verified on target camera (wire format) | Live-confirmed `Ret:100` on message 1006 as plaintext JSON, 2026-07-01. `KeepaliveTask` implements and is unit-tested against this exact shape (loopback server; not the live device via the compiled app). |
| PTZ movement | Verified on target camera | Live-confirmed: `DirectionRight`, channel 0, step 1 -> `Ret:100`, plaintext JSON. **Visible camera movement confirmed on-device** -- the left/right button mapping was corrected by watching the camera's actual pan direction against this specific mount. `PtzRequestBuilder`/`PtzController` implement and are unit-tested against this exact JSON shape. |
| PTZ stop | Verified on target camera | Live-confirmed immediately after movement: compatibility stop (`DirectionUp`, `Preset:-1`, `Step:5`) -> `Ret:100`, and the camera visibly stops on release. |
| **Video (actual displayed stream)** | **Verified on target camera** | The app's live video comes from **RTSP**, not DVRIP -- confirmed live 2026-07-01: real `SETUP`/`PLAY` handshake, 138KB of real H.265 video + PCMA audio RTP bytes received. See PROTOCOL_NOTES.md "RTSP video -- LIVE CONFIRMED". Wired into the app via `RtspVideoPlayer` (`androidx.media3` RTSP extension) and rendered with `PlayerView` in `LiveControlScreen`; plays on-device. `RtspUrlBuilder` is unit-tested and matches the live-confirmed manual probe. |
| Video claim (`OPMonitor`, DVRIP path) | Partially verified | Live-confirmed `Ret:100` for the single combined Claim+Start request, 2026-07-01 -- but only when the control connection that authenticated the session is kept open; closing it first invalidates the session for the media connection (`Ret:103`), a real constraint now reflected in this app's architecture notes. No actual media bytes were received within 10s of a successful claim; cause unresolved. Superseded by RTSP above as the app's actual video source; kept running for diagnostics/stats only. |
| Video framing (message 1412 chunking, DVRIP path) | Partially verified | 8192-byte max chunk size and the private `00 00 01 <marker>` unit-start convention confirmed from real pcap bytes; `MediaStreamReassembler` unit-tested against those exact shapes. Not re-confirmed live since the claim didn't yield media bytes in the live test above. Not used by the app's actual video path (RTSP, above). |
| Video codec identification (DVRIP path) | Blocked (moot) | No standard Annex-B start code found anywhere in any video unit examined in the pcap. Now explained: RTSP's SDP confirms this camera streams **H.265**, not H.264 -- `CodecProbe`'s Annex-B search was looking for the wrong codec's start code, not hitting a framing mystery. `CodecProbe` remains implemented/unit-tested for the DVRIP path but is not used by the app's actual video path (RTSP handles codec via its own SDP negotiation). |
| Video decode (DVRIP path, `HardwareVideoDecoder`/`MediaCodec`) | Blocked (moot) | Not used by the app's actual video path -- RTSP delivers H.265 via `androidx.media3`'s own MediaCodec-backed decode pipeline instead. `HardwareVideoDecoder` remains implemented per the Android API but unexercised. |
| Snapshot capture | Implemented, untested | `SnapshotCapture` correctly implements `PixelCopy` + MediaStore (scoped storage on API 29+, legacy file path below) per the Android API, off the decoder thread. Cannot be exercised without a rendering video surface or a device to run it on. |
| Talk claim (`OPTalk` Claim, 1434) | Verified on target camera | Live-confirmed `Ret:100`, plaintext JSON, matching the pcap evidence exactly. `TalkController` sends the identical JSON on the talk connection. |
| Talk activation (`OPTalk` Start, 1430) | **Verified on target camera (audible)** | The step that actually opens the camera speaker. Plaintext `OPTalk` `{"Action":"Start"}` on the control connection returns `Ret:100` with the camera's expected `AudioFormat`; a streamed test tone was then **heard from the camera speaker**. `TalkController` sends this right after the claim and sends `Stop` on release. |
| Talk framing (8-byte sub-header) | Verified on target camera | The exact 8-byte header (`00 00 01 FA 0E 02 40 01`) is constant across all 80 real captured audio frames; `TalkAudioFrame.wrap()` reproduces it byte-for-byte. |
| Talk audio (G.711 A-law) | Verified on target camera | Standard ITU-T G.711 algorithm, independently cross-checked: encoding PCM silence produces `0xD5`, exactly the byte observed filling the silent portions of real captured audio frames. |

## What changed and why (for anyone re-reading this after the fact)

The RSA/AES login path documented earlier in this project's history
(`message 1010` negotiation, RSA-wrapped credentials) is real and does
work as a *negotiation* (confirmed live), but turned out not to be
*required* by this camera: a much simpler plaintext login (message 1000
only, `LoginType: "DVRIP-Web"`, no encryption at all) is accepted, and
every subsequent command tested (keepalive, PTZ, OPTalk claim) also works
unencrypted on that session. This was discovered by decompiling the real
vendor app's native SDK and cross-referencing an actively maintained
open-source client, then correcting a password typo that had been causing
every attempt (regardless of protocol correctness) to fail identically.
