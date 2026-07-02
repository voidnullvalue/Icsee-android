# Testing

## Unit tests

```bash
cd /root/icsee-local-camera
./gradlew testDebugUnitTest
```

98 tests, all passing as of the last verified build (see the end of
`README.md` for the exact run and APK hash). Covers, per file:

- `dvrip/` -- 20-byte header encode/decode (byte-for-byte against real
  captured frames), invalid magic / excessive payload length rejection,
  partial-frame reassembly, multiple frames in one buffer, JSON vs base64
  payload terminator handling, a real loopback-socket transport
  round-trip.
- `discovery/` -- little-endian `HostIP` conversion (task brief's own
  worked example), beacon JSON parsing, the client probe frame's exact
  bytes.
- `crypto/` -- RSA public key parsing against the real captured modulus,
  pre-login negotiation parsing against the real captured 1010/1011
  exchange, AES round-trips and `shouldEncrypt` against the real
  `NotEncryptMsgID` list, `SofiaHash` against real live-confirmed vectors
  (see "Headline result" in PROTOCOL_STATUS.md).
- `session/` -- the login negotiator against a loopback server replaying
  the real captured successful-login response shape (and a real rejection
  shape), keepalive scheduling/cancellation, reconnect backoff bounds, the
  full session state machine end to end through to `Authenticated`.
- `ptz/` -- `OPPTZControl` JSON shape byte-for-byte, `ZoomTile` spelling,
  step/preset validation, tour flag, the compatibility stop shape.
- `video/` -- media unit reassembly against the real captured chunk sizes,
  codec probe (confirms `UNKNOWN` on real-shaped bytes, confirms detection
  works on synthetic Annex-B bytes).
- `audio/` -- G.711 A-law known vectors (including the real captured
  silence byte `0xD5`), the talk frame's exact 8-byte sub-header.

None of these require network access or Android framework classes that
throw when unmocked (`isReturnDefaultValues=true` is set for the ones that
do touch stub Android classes, but the bulk of the logic is deliberately
kept in plain Kotlin so it runs as fast plain-JVM unit tests).

## Lint

```bash
./gradlew lintDebug
```

## Full build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## pcap analysis tooling (not live tests, but reproducible)

```bash
python3 tools/pcap/reassemble_follow.py <followraw.txt>
python3 tools/pcap/export_sanitized_metadata.py <followraw.txt>
```

See `PROTOCOL_NOTES.md` for how to regenerate the `followraw.txt` inputs
from `/root/pcap.pcap` with `tshark`.

## Live hardware tests (opt-in)

Nothing in the Android app's test suite touches real hardware automatically
-- this is enforced structurally, not just by convention: `BuildConfig`
fields `ICSEE_TEST_HOST`/`PORT`/`USERNAME`/`PASSWORD` default to empty
strings unless you provide them.

To opt in:

```bash
cp local-test.properties.example local-test.properties
# edit local-test.properties with real values, or export the equivalent
# ICSEE_TEST_* environment variables (these take precedence)
```

`local-test.properties` is gitignored. Never commit real credentials.

### Read-only protocol probes already run against the real camera

Standalone Python scripts under `tools/live/` used to resolve protocol
unknowns during development, including the final successful login probe.
Anything that submits credentials reads them from environment variables /
`local-test.properties`, never hardcoded. Safe to re-run.

```bash
python3 tools/live/probe_1010.py 192.168.1.100 34567
python3 tools/live/probe_legacy_login.py 192.168.1.100 34567
```

Recorded results (2026-07-01, see PROTOCOL_NOTES.md "Login -- LIVE
AUTHENTICATION CONFIRMED" for the full story):

- `probe_1010.py`: message 1010 -> message 1011, `Ret: 100`, real RSA-1024
  public key + AES-128 params returned. Key rotates between calls.
- `probe_legacy_login.py`: legacy plaintext login with the *wrong* password
  hash algorithm -> message 1001, `Ret: 205`.
- **Real login succeeded**: plaintext JSON message 1000 with
  `LoginType: "DVRIP-Web"` and the password run through `SofiaHash` ->
  message 1001, `Ret: 100`, real `SessionID`/`AliveInterval`/`AdminToken`.
- Real keepalive (1006), PTZ movement + compatibility stop (1400), and
  `OPTalk` claim (1434) all confirmed `Ret: 100` in the same session, all
  plaintext (no encryption needed for this login path).
- `OPMonitor` claim (1413) confirmed `Ret: 100`, but only while the control
  connection stays open; no media bytes received within 10s in the test run
  -- see PROTOCOL_STATUS.md.

### Live validation order for the Android app

The compiled app runs on real Android hardware; login, PTZ, RTSP video and
push-to-talk work on-device. The byte-level protocol evidence below comes
from the Python probes (using the identical algorithm the Kotlin code
implements) and the reference capture.

1. TCP connection -- confirmed reachable.
2. Message 1010 -- confirmed (real RSA/AES params parsed); not on the login path actually used.
3. RSA public-key extraction -- confirmed.
4. AES negotiation -- not needed for this camera's actual login path (confirmed unencrypted).
5. Authentication -- **confirmed, `Ret: 100`**.
6. Session ID -- confirmed (`0x1d`/`0x1e`/`0x1f`/... across several live logins).
7. Keepalive (1006) -- **confirmed, `Ret: 100`**.
8. PTZ movement step 1 -- **confirmed, `Ret: 100`**, and visible camera
   movement confirmed on-device.
9. PTZ stop -- **confirmed, `Ret: 100`**, sent immediately after step 8; the
   camera visibly stops on release.
10. `OPMonitor` claim (DVRIP video) -- confirmed `Ret: 100` (control connection must stay open), but no media bytes ever arrived in live testing -- this DVRIP video path is not what the app actually uses.
11. Binary video receipt -- **achieved via RTSP instead of DVRIP**: 138KB of real H.265 video + PCMA audio RTP bytes confirmed live over a full `SETUP`/`PLAY` handshake. See PROTOCOL_NOTES.md "RTSP video -- LIVE CONFIRMED".
12. Codec detection -- **confirmed via RTSP's SDP**: H.265 video, PCMA audio. (The DVRIP path's codec probe remains blocked/moot -- it was looking for H.264 Annex-B start codes, and this camera streams H.265.)
13. Decoded video -- delegated to `androidx.media3`'s RTSP + MediaCodec pipeline; plays on-device.
14. `OPTalk` claim -- **confirmed, `Ret: 100`**.
15. Push-to-talk -- **confirmed audible on the camera speaker.** The claim
    (1434) alone is silent; a plaintext `OPTalk` `Start` (1430) on the control
    connection opens the speaker, after which streamed G.711 A-law frames play
    out loud. A streamed test tone was heard from the camera.

### RTSP video probe (2026-07-01)

```bash
python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.connect(('192.168.1.100', 554))
s.sendall(b'DESCRIBE rtsp://192.168.1.100:554/user=admin&password=&channel=1&stream=0.sdp RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\n\r\n')
print(s.recv(4096).decode())
"
```

Returns `200 OK` with a real SDP confirming H.265 video + PCMA audio.
`admin` with a blank password is a real, reproducible factory-default RTSP
credential on this camera, separate from its DVRIP account -- see
PROTOCOL_NOTES.md. A full `SETUP`/`PLAY` handshake (not reproduced here,
see conversation history / `RtspVideoPlayer.kt`) confirmed real RTP bytes
flowing on both the video and audio channels.

### Safety notes for PTZ live testing

- Do not repeatedly drive the camera into its mechanical limit.
- Step 1 + stop were confirmed via `Ret: 100` only; escalate to step values
  2/5/10 only after independently confirming visible movement and stop are
  correct (this app's development environment has no camera view, so that
  confirmation is still owed).
- `PtzController`'s queuing guarantees stop is never silently dropped in
  favor of a newer queued movement command (see its class doc and
  `ptz/PtzController.kt`); this logic has been reasoned through and unit
  tested but not exercised against rapid real-world press-and-hold input.
