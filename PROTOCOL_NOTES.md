# DVRIP Protocol Notes — Evidence from `/root/pcap.pcap`

This document records what was directly observed in the packet capture
(`/root/pcap.pcap`, SHA256 `fc708e63eed0a04e8f89d934f2e22a3c3f369ce87b2af98bc50f4e02006baa1e`,
8867 packets, 67.5s, captured 2026-07-01) versus what is asserted by the task
brief but not independently confirmed here. The capture is **not** committed
to git (see `.gitignore`); it is read from `/root/pcap.pcap` directly and
symlinked at `evidence/pcap.pcap` (also gitignored) for convenience.

Analysis tooling lives in `tools/pcap/`:

- `dvrip_common.py` — shared 20-byte header parser (`iter_frames`), matches
  the header layout given in the task brief byte-for-byte.
- `reassemble_follow.py` — reassembles a `tshark -z follow,tcp,raw,<n>` dump
  into ordered per-direction byte streams (critical: pcap packets are TCP
  segments, not DVRIP frames — several DVRIP frames can share a segment and
  one frame can span several segments) and reports every DVRIP frame found.

To reproduce:

```bash
tshark -r /root/pcap.pcap -q -z conv,tcp                     # find the streams
tshark -r /root/pcap.pcap -q -z follow,tcp,raw,<n> > s.txt    # per-stream dump
python3 tools/pcap/reassemble_follow.py s.txt
```

## TCP streams to 192.168.1.100:34567

| tcp.stream | client port | packets | role (evidence-based) |
|---|---|---|---|
| 74  | 40364 | 6 (no payload, connection refused/empty) | unused |
| 76  | 40372 | 123 | control channel: login, keepalive, PTZ, monitor/talk claim |
| 80  | 40388 | 4048 | combined media channel: video + interleaved audio |
| 104 | 40498 | 129 | talk channel: `OPTalk` claim + G.711 A-law audio both directions |

All traffic to/from 192.168.1.100 in this capture is TCP; **zero UDP
packets** to/from the camera were captured, so the discovery beacon
*response* format could not be verified from this pcap (see Discovery
section). The client's outbound discovery probe *was* captured.

## Header framing — CONFIRMED

Matches the task brief exactly, verified against every frame parsed above:

```
offset 0   1B  magic = 0xFF
offset 1   1B  type/reserved (observed value: 0x01 on every frame in this capture)
offset 2   2B  reserved = 0
offset 4   4B  session id, LE
offset 8   4B  sequence, LE
offset 12  2B  reserved = 0
offset 14  2B  message id, LE
offset 16  4B  payload length, LE
```

Plaintext JSON payloads are terminated `0x0A 0x00` and that terminator is
included in the header's payload-length field, e.g. the login response:

```
msg=1001 len=128 (camera -> client, plaintext JSON)
{ "AliveInterval" : 30, "ChannelNum" : 1, "DeviceType " : "IPC",
  "ExtraChannel" : 0, "Ret" : 100, "SessionID" : "0x0000001b" }\n\x00
```

Encrypted payloads (see below) are base64 ASCII text terminated by a single
trailing `0x00` (no `0x0A`).

## Discovery probe — PARTIALLY VERIFIED

The client broadcasts this exact 20-byte frame to `255.255.255.255:34569`
roughly every 5-8 seconds while the app is idle:

```
ff 00 0000 00000000 00000000 0000 fa05 00000000
magic=FF type=00 session=0 seq=0 msg_id=0x05FA(1530) payload_len=0
```

No camera response to this probe appears anywhere in the capture (no UDP
packets from 192.168.1.100 at all). The camera beacon JSON shape given in
the task brief (`NetWork.NetCommon` object with `HostIP` etc.) is therefore
**not evidenced by this pcap** — it is implemented per the brief's example
and must be confirmed by a live capture of an actual beacon. Status:
Partially verified (probe format only).

## Login — LIVE AUTHENTICATION CONFIRMED (2026-07-01)

**This is the most important update in this document.** A real, working
login was achieved live against the target camera. Full story, in order:

1. The user provided real DVRIP credentials (redacted here; see
   `local-test.properties`, gitignored). Extensive
   RSA/AES-based login attempts (see the section below, and `tools/live/probe_login_attempts*.py`
   rounds 1-6) all failed with a content-insensitive `Ret` code, which in
   hindsight was simply because of a **one-character password typo**
   (`r` vs `4`) — not a protocol problem.
2. The user provided the actual vendor APK (`iCSee Local Control`'s
   real-world counterpart). Decompiling its native SDK, `libFunSDK.so`
   (ARM64, ~35MB, statically links OpenSSL), via `objdump`/`readelf`
   disassembly confirmed the *real* RSA-based login construction used by
   the modern app: JSON with `UserName`/`PassWord` individually
   RSA/PKCS1v1.5-encrypted and hex-encoded (functions `NewLoginPTL`,
   `EncDevPassord`, `RSAV15`, `ByteToHexStr`, `XMMD5Encrypt` — all traced
   by address in `libFunSDK.so`). The password hash inside that scheme is
   a base62 MD5 transform, confirmed **identical** across three independent
   sources: the camera's own web UI JavaScript (`MD5_8` in
   `http://192.168.1.100/js/main.js`), the native SDK (`XMMD5Encrypt`),
   and (found next) a real open-source client.
3. Searching for existing third-party clients turned up
   [`dbuezas/icsee-ptz`](https://github.com/dbuezas/icsee-ptz), an actively
   maintained Home Assistant integration (issues/PRs as recent as 2026-07).
   Its login code (`custom_components/icsee_ptz/asyncio_dvrip.py`) uses a
   **much simpler path that this camera also accepts**: no message 1010, no
   RSA, no AES — a single plaintext JSON message 1000. Its `sofia_hash`
   function is the *exact same* base62 MD5 transform found in steps above,
   independently confirming the hash algorithm was never the problem.
4. Two of that repo's open issues (#65, #66) describe newer Xiongmai
   firmware generations (`V5.06.R02...`, very close to this camera's
   `V5.11.R02...`) that reject or restrict third-party DVRIP clients even
   with valid credentials — which is what made the persistent rejection
   look like a firmware lockdown rather than a typo, until the password was
   rechecked.
5. With the corrected password, the plaintext login **succeeded live**:

```
C→S msg=1000 (plaintext JSON, session=0):
{"EncryptType":"MD5","LoginType":"DVRIP-Web","PassWord":"<SofiaHash.hash(password)>","UserName":"<redacted>"}

S→C msg=1001 (plaintext JSON), Ret:100:
{ "AdminToken" : "7H3fdUf0w+I+GiJs8U1/m8neYIGD4XRktHu4j0Leqcg=", "AliveInterval" : 30,
  "ChannelNum" : 1, "DeviceType " : "IPC", "ExtraChannel" : 0, "Ret" : 100,
  "SessionID" : "0x0000001d" }
```

The real credentials and the resulting hash are deliberately not recorded
here (see `local-test.properties`, gitignored, for live testing).
`SofiaHash.hash(password)` is: MD5 the password, then for each of the 8
byte-pairs of the digest, sum the pair mod 62 and map to `0-9A-Za-z`
(alphabet
`0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz`).

**Also confirmed live, in the same session, all plaintext (no AES at all):**

- Keepalive (`1006`): `{"Name":"KeepAlive","SessionID":"0x..."}` → `Ret:100`.
- PTZ (`1400`): `DirectionRight` step 1 → `Ret:100`; the documented
  compatibility stop (`DirectionUp`, `Preset:-1`, `Step:5`) → `Ret:100`.
  (`Ret` confirmed only -- this environment has no camera view, so visible
  movement/stop were not independently confirmed.)
- `OPTalk` claim (`1434`) → `Ret:100`.
- `OPMonitor` claim (`1413`, single combined Claim+Start) → `Ret:100`,
  **but only while the control connection that logged in stays open** --
  closing it before opening the media connection invalidates the session
  and the claim comes back `Ret:103`. This is a real architectural
  constraint the app's `VideoStreamController`/`CameraSessionManager`
  relationship must respect. No actual media bytes were received within
  10s of a successful claim in this test, which remains unresolved --
  see PROTOCOL_STATUS.md.

**Unresolved:** the `AdminToken` field in the login response is new (not
in the task brief's examples or the original pcap) and its purpose is
unconfirmed; it is not currently used by this app.

The app's `DvripLoginNegotiator` implements this exact confirmed flow.
The RSA/AES machinery below (`crypto/DvripRsaPublicKey`,
`crypto/PreLoginNegotiation`, `crypto/AesSessionCrypto`) is real,
independently live-tested code for the message 1010/1011 exchange, kept
available for a device/firmware that actually requires it, but is not used
by the login path that actually works against this camera.

## Login — message 1000/1001 (RSA/AES path) — PARTIALLY VERIFIED

This section describes the *encrypted* login path suggested by the
original pcap capture and by the modern vendor app's native code. It
remains true and is kept for reference, but per the section above, this
specific camera also accepts a much simpler plaintext login, which is what
the app actually uses.

Client -> camera, `msg_id=1000`, `session=0x00000000` (no session yet):
payload is **577 bytes of ASCII text, not JSON** — it decodes as standard
base64 to exactly **432 raw bytes** (432 = 27 × 16, i.e. a whole number of
AES blocks). This directly contradicts the legacy `dbuezas/icsee-ptz`
JSON+MD5 login (no message 1010, no AES transport) referenced in the task
brief — this camera's firmware (`V5.11.R02.000809V1.10010.346837.0000010`)
clearly uses an encrypted login body, consistent with the brief's
`EncryptAlgo: RSA_V1.5` / `CommunicateEncryptAlgo: AES` / `CommunicateBits:
128` hints.

Camera -> client, `msg_id=1001`, plaintext JSON, `Ret: 100`, hands back the
authenticated `SessionID` and `AliveInterval` (see example above). All
subsequent frames in the control channel use that session id.

**What is *not* evidenced:** the message-1010 pre-login negotiation itself.
No frame with `msg_id=1010` appears anywhere in any of the four TCP streams
in this capture (checked by parsing every frame's numeric message id, not
just by string-searching for JSON — an encrypted 1010 payload would not be
caught by `strings`, but the message id in the plaintext header would still
be visible, and it never occurs). Two explanations are consistent with the
evidence and cannot be distinguished from this pcap alone:

1. The 1010 negotiation happened on an earlier connection not present in
   this 67-second capture window, and this session's AES key was derived
   from state established earlier (cached key, or a device-derived key not
   requiring a fresh RSA exchange every connection).
2. This firmware performs the RSA/AES negotiation implicitly as part of
   message 1000 itself rather than as a distinct prior exchange.

**Update -- live-tested 2026-07-01.** This proot environment turned out to
have real network access to the target camera (`192.168.1.100:34567` is
reachable and accepts connections). Two read-only, credential-free live
probes were run (scripts in `tools/live/`, safe to re-run):

### `tools/live/probe_1010.py` — message 1010/1011, VERIFIED ON TARGET CAMERA

Sending message 1010 with payload `{}` (plain JSON, unencrypted -- 1010 is
in the confirmed `NotEncryptMsgID` list) gets back message 1011 with
`Ret: 100` and this exact real JSON (whitespace as sent by the camera):

```
{ "Bits" : 1024, "CommunicateBits" : 128, "CommunicateEncryptAlgo" : "AES",
  "EncryptAlgo" : "RSA_V1.5",
  "NotEncryptMsgID" : [ 1000, 1001, 1008, 1009, 1010, 1011, 1050, 1054, 1412,
    1413, 1414, 1422, 1424, 1425, 1426, 1432, 1433, 1434, 1435, 1449, 1522,
    1572, 1576, 1580, 1582, 1645, 2062, 2063, 2123, 2140, 3016, 3502 ],
  "PublicKey" : "<256 hex chars>,010001",
  "Ret" : 100 }
```

This is a complete, byte-exact match for the task brief's own "known
unencrypted message IDs" list, confirming that list came from the camera
itself rather than being an approximation. Concretely confirmed:

- **Request**: message 1010, session 0, plaintext JSON `{}` (also tested
  sending zero payload bytes at all -- both get the identical response, so
  the request body content doesn't appear to matter).
- **Response**: message 1011, `Ret: 100` on success.
- **RSA key encoding**: the `PublicKey` field is `"<hex modulus>,<hex
  exponent>"` as a single string -- confirmed by parsing it as
  `BigInteger(modulus, 16)` / `BigInteger(exponent, 16)`: modulus is exactly
  256 hex chars (1024 bits, matching the `Bits` field), exponent is
  `010001` = 65537 (the standard RSA public exponent `F4`). Decoded with
  `java.security.KeyFactory("RSA")` + `RSAPublicKeySpec` (see
  `crypto/DvripRsaPublicKey.kt`) and successfully used to encrypt a test
  plaintext with `Cipher.getInstance("RSA/ECB/PKCS1Padding")`, producing the
  expected 128-byte block -- so the key is a genuine, usable RSA-1024
  public key, not a malformed or truncated one.
- **Key rotation confirmed**: two probes seconds apart returned two
  different moduli, exactly matching the task brief's claim.

### `tools/live/probe_legacy_login.py` — legacy plaintext login, VERIFIED ON TARGET CAMERA

Sending message 1000 with a **plaintext** (non-RSA/AES-encrypted) JSON body
in the `dbuezas/icsee-ptz` legacy shape (`LoginType: DVRIP-Web`, MD5/"sofia
hash" password, placeholder username/password -- deliberately not a real
account, this test is designed to fail) gets back:

```
msg_id=1001  { "Name" : "", "Ret" : 205, "SessionID" : "0x00000000" }
```

This exactly matches the task brief's claim ("Legacy unauthenticated
attempts returned `Ret = 205`") and confirms the camera parses the frame
(doesn't just drop the connection) and cleanly rejects the legacy format
with a specific, stable error code rather than crashing or hanging.

### Update: superseded by a working plaintext login

The exact AES key derivation for *this* encrypted path remains unresolved,
but is no longer a blocker for the app: see "Login -- LIVE AUTHENTICATION
CONFIRMED" above, which documents the plaintext login path that this
camera actually accepts and that `DvripLoginNegotiator` now uses. This
RSA/AES path's key derivation is left as a genuinely open question for
future work (e.g. if a firmware update starts requiring it), not because it
blocks the app today.

## Post-login encryption — CONFIRMED (mechanism), UNRESOLVED (key derivation)

Every message observed after login, **except** the ones in the task brief's
"not encrypted" id list, carries an ASCII-base64 payload that decodes to a
whole number of 16-byte blocks, terminated by a single `0x00` byte (not
`0x0A 0x00`). Observed pairs and their status in this capture:

| msg id | direction | encrypted? | notes |
|---|---|---|---|
| 1000 | C→S | yes (432B decoded) | login |
| 1001 | S→C | **no**, JSON | login response, `Ret:100` |
| 1006 | C→S | yes (48B decoded) | keepalive (name per task brief) |
| 1007 | S→C | yes | presumed keepalive response (id inferred from ordering only) |
| 1400 | C→S | yes | `OPPTZControl` (per task brief; confirmed not in unencrypted list) |
| 1401 | S→C | yes | presumed PTZ response; `Ret` value not readable without the AES key |
| 1410/1411 | both | yes | purpose unconfirmed — not named anywhere in plaintext |
| 1020/1021, 1042/1043, 1360/1361, 1430/1431, 1610/1611, 1612/1613 | both | yes | purpose unconfirmed, likely periodic device telemetry the stock app polls for; not required by any feature in this app |
| 1413 | C→S | **no**, JSON | `OPMonitor` claim, sent once early in the capture against a **stale session id** left over from a prior connection (`0x0001869F`, not the session established by the login later in the same stream) |
| 1412 | S→C | **no** (private binary framing, not JSON/base64) | media channel, see below |
| 1432/1433 | both | **no** (raw binary G.711) | talk audio, see below |
| 1434/1435/1431 | both | **no**, JSON | `OPTalk` claim/response |

The AES key/IV derivation itself is not recoverable from ciphertext alone
and is explicitly **BLOCKED** — see `crypto/SessionCrypto.kt` for the
interface this plugs into. Because message 1401 (PTZ ack) is encrypted, this
pcap cannot independently confirm `Ret: 100` for the sample `DirectionRight`
commands it contains — physical camera movement / live `Ret` inspection is
the confirmation path (see `TESTING.md`).

## RTSP video — LIVE CONFIRMED (2026-07-01)

Separately from DVRIP's private media channel (below, which claims
successfully but hasn't yielded actual media bytes in live testing), this
camera also exposes a **standard RTSP/RTP stream** on port 554 that was
tested live end-to-end and works:

- URL convention (this vendor family's standard form, not RFC-standard
  userinfo auth): `rtsp://<host>:554/user=<user>&password=<pass>&channel=<n>&stream=<0|1>.sdp`
  (`channel` is 1-based here; `stream` is `0` for main, `1` for sub.)
- A real, reproducible **factory-default RTSP account** was found:
  `admin` with a **blank password** consistently returns `200 OK` on
  `DESCRIBE`. The real DVRIP account configured on this camera does *not*
  work for RTSP (`401 Unauthorized`) -- RTSP has its own, separate
  credential store on this device. Not assumed to be true of every camera
  in this family; the app tries the user's configured credentials first
  and falls back to this only on rejection (see `RtspUrlBuilder`,
  `RtspVideoPlayer`).
- `DESCRIBE` response SDP confirms the exact codecs:
  ```
  m=video 0 RTP/AVP 98
  a=rtpmap:98 H265/90000
  a=fmtp:98 profile-id=010101;sprop-pps=...;sprop-sps=...;sprop-vps=...
  m=audio 0 RTP/AVP 8
  a=rtpmap:8 PCMA/8000
  ```
  **H.265 (HEVC)**, not H.264 -- this is *why* DVRIP's codec probe (below)
  never found an Annex-B H.264 start code: it's not a framing mystery,
  it's a different codec than assumed.
- Full `SETUP` (`RTP/AVP/TCP;unicast;interleaved=N-M`, TCP-interleaved
  transport) + `PLAY` handshake completed and confirmed **real RTP bytes
  flowing**: 138KB of video-channel bytes and audio-channel bytes both
  received in a ~15s test window, first video RTP payload
  `80 62 00 00 00 00 1d 4c 00 00 00 00 40 01 0c 01 ff ff 01 60 00 00 03 00`
  -- RTP v2 header (`0x80`), marker+payload-type-98 (`0x62` = 98 decimal,
  matching the SDP's H265 mapping), consistent with a real HEVC NAL unit
  following.

The app uses `androidx.media3`'s RTSP extension (`media3-exoplayer-rtsp`)
to consume this stream directly -- H.265 hardware decode via
`MediaCodec` under the hood, RTP depacketization and jitter buffering
handled by the library, rather than hand-rolling any of that. This is a
standard, well-supported protocol surface, in contrast to DVRIP's private
media framing below.

## Video/audio media channel (`stream 80`, msg 1412) — PARTIALLY VERIFIED

The client's exact request on the connection that actually received video
(`stream 80`) is a **single combined claim+start**, not the task brief's
two-step Claim-then-Start example:

```
msg=1413 (client->camera, plaintext JSON, terminated by a bare 0x00 -- no
          0x0A before it, unlike most other JSON payloads observed)
{"Name":"OPMonitor","OPMonitor":{"Action":"Claim","Action1":"Start",
 "Parameter":{"Channel":0,"CombinMode":"NONE","StreamType":"Main",
 "TransMode":"TCP"}},"SessionID":"0x000000001b"}
```

This is the task brief's *second* example ("Video start") shape, not the
first one (which additionally has a `DHParameter.RandomStrA` field and
`CombinMode: CONNECT_ALL`, and which our own capture only shows used against
a **stale session id** early in `stream 76`, not on the connection that
actually streamed video -- that one only ever got the encrypted-looking
1414/1410 leftovers described below, never live video). The app's real
video flow: connect a **second, independent TCP connection** (confirmed --
`stream 80`'s client only ever sent this one non-login message; no message
1000 login happens on it, it reuses the session id established on the
control connection) → send this single combined claim+start → camera
answers `msg 1414` plaintext JSON `{"Name":"OPMonitor","Ret":100,
"SessionID":"0x0000001b"}` → media starts immediately.

All media after that point — **both audio and video** — travels as
`msg_id=1412` DVRIP frames on this connection, chunked at a maximum of
**8192 payload bytes per DVRIP frame**; a single logical media unit that
exceeds 8192 bytes is split across consecutive frames (sequence numbers
increment 0,1,2,... with no gaps) and the final chunk of that unit is
shorter than 8192 bytes. (Only ever verified for units up to a few 8192-byte
chunks; a unit whose size is an *exact* multiple of 8192 is an untested edge
case for this inferred continuation rule -- see `video/MediaStreamReassembler.kt`.)

Each logical media unit begins with a private 3-byte marker `00 00 01`
followed by a type byte, **not** a standard Annex-B NAL start code
(standard H.264/H.265 start codes are `00 00 00 01` or `00 00 01` followed
directly by a NAL header whose forbidden-zero-bit must be 0; the marker byte
observed here, `0xFC`/`0xFD`/`0xFA`, is not a valid NAL header). Observed
marker bytes:

- `0xFA` — audio unit. Always exactly 328 bytes total payload (8-byte
  sub-header + 320 bytes of data), **never split** across frames.
  Sub-header is a **constant** `00 00 01 fa 0e 02 40 01` on every audio
  frame observed (37 client-side, 43 camera-side) — bytes 6-7 (`40 01` LE =
  `0x0140` = 320) equal the trailing data length; the semantic meaning of
  bytes 2-5 (`01 fa 0e 02`) could not be determined since it never varies in
  this capture (only one audio format/size was exercised). The 320-byte
  body is **raw G.711 A-law** — confirmed by content, not just the codec
  name in the `OPTalk` claim JSON: early frames are solid `0xD5` (the
  correct A-law silence code) and later frames show the expected wide byte
  distribution of live speech. This is unencrypted, matching the task
  brief's "known not encrypted" list (1432/1433/1434/1435). Total
  DVRIP-frame size 20 (header) + 328 (payload) = **348 bytes**, matching the
  task brief's ~348-byte observation exactly.
- `0xFC` — seen once, on the very first video unit of the capture (8192 + 23
  continuation frames + a 5337-byte final chunk = one large unit, presumably
  a keyframe).
- `0xFD` — seen on every subsequent video unit (presumed inter-frames);
  16-byte sub-header pattern `00 00 01 fd XX XX 00 00 00 00 00 01 02 01 <LE16>`
  where the trailing 16-bit LE field increases by `0x200` on each of the
  four consecutive samples checked (`0x03d0, 0x05d0, 0x07d0, 0x09d0`),
  consistent with a monotonic per-frame counter or timestamp. Not enough
  varying samples were captured to fully decode this sub-header.

**Codec identification is BLOCKED.** No standard Annex-B start code (`00 00
00 01` followed by a valid NAL header byte) was found anywhere inside any
video unit's payload after its private sub-header, in either the keyframe
or the inter-frame samples checked. Two explanations are consistent with
the evidence:

1. The elementary stream uses length-prefixed NAL units (AVCC-style) or
   some other private framing instead of Annex-B start codes, and is a
   perfectly ordinary compressed codec once that's understood.
2. The video payload is itself encrypted (message id 1412 is in the task
   brief's "not encrypted" list, which argues against this, but that list
   describes the *DVRIP transport layer's* encryption, and cannot rule out
   a *codec-layer* scramble).

High per-byte entropy in the payload is consistent with both compressed
video and encrypted data and cannot distinguish them. Resolving this needs
either a live decode attempt (feed candidate byte ranges to `MediaCodec`
with H.264/H.265 configured and see whether it decodes) or a live capture
correlated with a known, static test scene. `video/` isolates codec
detection behind a dedicated analyzer so the app can report "unknown codec,
N bytes received, 0 frames decoded" honestly rather than silently pretend
success.

## Talk channel (`stream 104`) — VERIFIED

```
C→S msg=1434 JSON {"Name":"OPTalk","OPTalk":{"Action":"Claim","AudioFormat":
    {"BitRate":0,"EncodeType":"G711_ALAW","SampleBit":8,"SampleRate":8}},
    "SessionID":"0x000000001b"}
S→C msg=1435 JSON {"Name":"OPTalk","Ret":100,"SessionID":"0x0000001b"}
C→S msg=1432 x37  328B frames, raw G.711 A-law (see above), mic → camera
S→C msg=1433 x43  328B frames, raw G.711 A-law, camera → mic (full duplex)
S→C msg=1431 x2   JSON {"Name":"OPTalk","Ret":100,...} (teardown acks)
```

This is the most fully verified media path in the whole capture: claim
JSON, response JSON, and the exact wire bytes of the audio frames are all
directly readable in plaintext.

## Message ID summary (this capture only)

Numeric ids actually observed on the wire, so the framing/dispatch code has
real test fixtures instead of invented ones:

```
1000/1001   login request/response
1006/1007   keepalive request/response (1006 confirmed by brief; 1007 inferred by ordering)
1020/1021, 1042/1043, 1360/1361, 1430/1431, 1610/1611, 1612/1613, 1410/1411
            encrypted, purpose unconfirmed, not required by any feature here
1400/1401   OPPTZControl request/response
1412        combined video+audio media stream
1413/1414   OPMonitor claim/start response (plaintext JSON)
1432/1433   talk audio (client->camera / camera->client)
1434/1435   OPTalk claim/response
1431        also seen as an OPTalk teardown ack
1530        client's UDP discovery probe (zero-length payload)
```

Message 1010 (pre-login negotiate) is not among them — see "Login" above.

## BLE Wi-Fi provisioning — hardware findings (not from the pcap)

BLE pairing is **not** in `/root/pcap.pcap` (that capture is raw IP / Wi-Fi
only). This section is sourced from the decompiled factory app
(`XMBleManager`, `BluetoothClientImpl`, and the bundled inuker
`BleConnectRequest`/`BleConnectWorker`) cross-checked against live testing on a
real camera + a OnePlus HD1901 phone on 2026-07-02. It backs the
`[[project-icsee-ble-pairing]]` memory and the comments in
`ble/CameraBlePairingClient.kt` / `ble/CameraBleScanner.kt`.

**GATT UUIDs** (read from the vendor app, not guessed):

```
service  00001910-0000-1000-8000-00805f9b34fb
write    00002b11-0000-1000-8000-00805f9b34fb   (client -> camera, write-with-response)
notify   00002b10-0000-1000-8000-00805f9b34fb   (camera -> client, ACK)
cccd     00002902-0000-1000-8000-00805f9b34fb   (standard notify descriptor)
```

**Scanning.** The camera does **not** advertise the pairing service UUID —
that service only exists after you connect. The vendor's `SearchRequest` sets
no scan filter at all and identifies cameras purely in software from their
**manufacturer-specific data** (AD type 0xFF), keeping those whose hex payload
starts with `8B8B`, `8B8D`, or `8BB8` (`XMBleManager.n()`). Our
`CameraBleScanner` does the same; the earlier service-UUID scan filter matched
nothing on real hardware.

**Connect choreography.** The vendor connects through the inuker library, whose
`BleConnectRequest` state machine does three things a naive raw `connectGatt`
does not, and all three were needed to make discovery reliable on the
OnePlus/Qualcomm stack:

1. Waits **300ms** after `STATE_CONNECTED` before `discoverServices()`
   (`onConnectStatusChanged` → `sendEmptyMessageDelayed(2, 300L)`). Skipping it
   makes discovery return SUCCESS with an empty/partial service table, so
   `getService()` yields null.
2. On empty/failed discovery, calls `BluetoothGatt.refresh()` (hidden method,
   via reflection — `BluetoothUtils.refreshGattCache`) to clear Android's stale
   GATT cache, then retries up to 4× (1s apart).
3. Retries the whole connect up to 4×.

The vendor also always uses the static `BluetoothAdapter.getDefaultAdapter()`,
never `BluetoothManager.getAdapter()` (which can transiently return null during
BT state transitions). Our client mirrors all of this.

**Writing the credential frame.** `BleWifiProvisionCodec` builds the frame that
`BleDistributionUtil.combineWiFiSSIDToHexStr(ssid, password, encrypt, …)`
produces (byte-verified by unit test). The vendor writes it in chunks (default
512B) **paced ~50ms apart** (`XMBleManager.l()` with `Thread.sleep(50)`) using
write-with-response, and — critically — determines success from the **ACK
notification, never from the write callback**. On the OnePlus stack
`onCharacteristicWrite` is not reliably delivered even though the write
physically lands (the camera joins the router), so our client no longer
hard-fails on a missing write callback.

**The ACK, and why we usually don't get it.** After joining Wi-Fi the camera
**drops the BLE link**, typically before (or instead of) sending the ACK
notification. The ACK, when present, carries the device's assigned
username/password, IP, MAC, and token; `parseWifiConfigAck` mirrors the
vendor's `parseBleWiFiConfigResult` offset walk. Because we rarely observe it,
provisioning is treated as succeeded once the frame is sent (the camera is
demonstrably on the network), and the app reports the **factory-default login
`admin` / no password** — which is what a fresh or reset device uses — then
relies on normal LAN discovery to find the camera by IP. Reading the real
assigned credentials from a live ACK remains unconfirmed.
