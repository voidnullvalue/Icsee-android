# AVTalk protocol reimplementation

This documents the AVTalk uplink implemented in `com.voidnullvalue.icseelocal.avtalk`.
It was recovered from the stock iCSee 7.9.1 APK supplied for issue #6 and cross-checked
against the packet-flow observations in that issue.

Reference artifacts used for the reverse engineering:

- APK bundle SHA-256: `147658bb037d50218cebfd940de577843ac51d782e786be7b8742914a2157bc4`
- ARM64 `libFunSDK.so` SHA-256: `d87e554ebd8c1e0824f7bed6e3e6a7d0e6718dad05ebea4cefd69ff031489429`

No vendor code or binaries are included in this repository. The implementation below is
an independent protocol reimplementation from observed/native behavior.

## Session flow

AVTalk uses two TCP connections to DVRIP port 34567.

### Control connection

1. `1413` — FunSDK encrypted-login negotiation probe.
2. `1000` — encrypted login; response `1001` establishes the DVRIP SessionID.
3. `1360` — encrypted `EncodeCapability`; response `1361`.
4. After the media connection successfully claims AVTalk, `1415` with `Action:"Start"`;
   response `1416`.
5. Send media on the secondary connection.
6. `1415` with `Action:"Stop"`; response `1416`.
7. Stock iCSee re-queries `1360` about two seconds after Stop.

The authenticated control connection stays alive for the whole AVTalk session and uses the
camera's `AliveInterval` keepalive cadence.

### Media connection

The second TCP connection does **not** log in. It reuses the control session's SessionID and
shares the session's command-sequence counter for its `1417` Claim. After Claim returns
`1418` with `Ret:100`, uplink frames use message `1419`.

The native order is therefore:

`1413 -> 1000 -> 1001 -> 1360 -> 1361 -> 1417 -> 1418 -> 1415 Start -> 1419... -> 1415 Stop -> 1360`

## Modern FunSDK login

### 1413 negotiation

The supplied build sends raw UTF-8 JSON with **no trailing NUL or LF**:

```json
{"Name":"OPMonitor","OPMonitor":{"Action":"Claim","Parameter":{"Channel":0,"CombinMode":"CONNECT_ALL","StreamType":"Main","TransMode":"TCP"}},"DHParameter":{"RandomStrA":"ABCD"},"SessionID":"0x1"}
```

DVRIP header details recovered from `NewGetLoginEncryTypePTL` / `InitMsg`:

- header SessionID: `99999`
- first request sequence: `1016` from the native global `CXMDevPTL::NewSeq()`
- header byte 12: `0x63`
- header byte 13: `0`

The response supplies `EncryptAlgo`, `PublicKey`, `CommunicateEncryptAlgo`,
`CommunicateBits`, `NotEncryptMsgID`, and optionally the `MD5_DH` / `RandomStrB` values.

### Credential and CommunicateKey wrapping

For `RSA_V1.5`, FunSDK parses `PublicKey` as `<modulus-hex>,<exponent-hex>` and encrypts
three values independently with RSA/PKCS#1 v1.5, serializing each ciphertext as uppercase hex:

- username
- the password transform
- the generated 16-character `CommunicateKey`

The ordinary password transform is the existing Xiongmai/Sofia 8-character MD5 transform.
When the camera advertises `MD5_DH`, FunSDK instead hashes:

`RandomStrA + rawPassword + RandomStrB`

The resulting message-1000 JSON is:

```json
{"EncryptType":"MD5","LoginType":"DVRIP-Web","UserName":"<RSA hex>","PassWord":"<RSA hex>","CommunicateKey":"<RSA hex>"}
```

`LoginType` defaults to `DVRIP-Web` in the recovered native path.

### AES envelope

The message-1000 JSON is encrypted using the static bootstrap key:

`dashoiahfarqdasr`

The exact native AES construction is:

1. UTF-8 JSON bytes.
2. Append the C-string NUL byte.
3. Zero-fill to the next 16-byte boundary.
4. AES-128-CBC with an all-zero IV and no cipher padding.
5. Base64-encode the ciphertext.
6. Append one NUL to the Base64 text on the DVRIP wire.

Message 1000 uses header SessionID `0` and header byte 12 `0x63`.

After a successful `1001`, the plaintext 16-character client-generated `CommunicateKey`
becomes the AES-128 session key. Protected post-login JSON commands use the same
C-string-NUL, zero-fill, CBC/zero-IV construction. Which message IDs bypass this generic
session envelope is taken from `NotEncryptMsgID` returned by the camera.

## AVTalk control JSON

For the profile recovered in issue #6:

```json
{"Name":"AVTalk","AVTalk":{"Action":"Claim","Channel":0,"ProType":0,"Video":{"Enc":"H265","W":240,"H":320,"FPS":10},"Audio":{"Enc":"g711a","SB":16,"SR":8000}},"SessionID":"0x000000003C"}
```

`Start` and `Stop` are the same object with only `Action` changed.

The supplied 7.9.1 FunSDK formats this SessionID as `0x%010X`. The issue capture used
lowercase hex; hexadecimal case does not change the value.

`1360` is exactly:

```json
{"Name":"EncodeCapability","SessionID":"0x000000003C"}
```

There is no nested `EncodeCapability` object in this native builder.

Wire envelopes:

- `1360`: session AES / Base64 / trailing NUL.
- `1415`: session AES / Base64 / trailing NUL; header byte 12 is Channel (`0` here).
- `1417`: plaintext AVTalk JSON plus **one NUL and no LF**; header byte 12 is Channel.
- `1419`: raw binary CSTDStream data.

## 1419 CSTDStream frames

### H.265 keyframe

16-byte header followed by H.265 Annex-B bytes:

```text
00 00 01 FC
cc ff ww hh
tt tt tt tt
ll ll ll 00
```

- `cc` low nibble: codec id; upper bits carry high width/height-unit bits.
- `ff` low five bits: FPS.
- dimensions are encoded in units of 8 pixels.
- timestamp is a little-endian packed calendar field:

```text
(year - 2000) << 26 |
month         << 22 |
day           << 17 |
hour          << 12 |
minute        <<  6 |
second
```

- `ll ll ll`: 24-bit little-endian Annex-B payload length.

This is **not a Unix timestamp** and the length is **not a 32-bit field**.

### H.265 inter-frame

```text
00 00 01 FD ll ll ll 00 <Annex-B>
```

The length is 24-bit little-endian.

### G.711 A-law audio

```text
00 00 01 FA cc rr ll ll <A-law samples>
```

For the issue-6 stock profile:

- `cc = 0x0E` (G.711 A-law)
- `rr = 0x02` (8000 Hz)
- 320 sample bytes represent 40 ms at 8 kHz

Recovered FunSDK sample-rate codes are:

| Hz | code |
|---:|---:|
| 4000 | 1 |
| 8000 | 2 |
| 11025 | 3 |
| 16000 | 4 |
| 20000 | 5 |
| 22050 | 6 |
| 32000 | 7 |
| 44100 | 8 |
| 48000 | 9 |

## DVRIP fragmentation and media grouping

`CMediaChannel` splits one CSTDStream source frame into DVRIP `1419` payloads no larger than
`0x10000` (65,536) bytes.

Every 1419 DVRIP packet uses:

- DVRIP sequence field: **0**
- header byte 13: `0`
- header byte 12: the low byte of the native per-source-frame group value

The group value comes from the same global `CXMDevPTL::NewSeq()` used by FunSDK
command traffic:

- initial state: 1008
- add 8 before returning
- if the value exceeds 10000, reset to 1008

The sequence is shared by `1413`, `1000`, `1360`, `1417`, `1415`, keepalives, and each
source media frame. `1419` itself still writes DVRIP sequence `0`; only its byte-12 group
tag comes from the generated source-frame sequence.

For the minimal AVTalk flow with no keepalive firing mid-session, the generated sequence is:

```text
1413        1016
1000        1024
1360        1032
1417 Claim  1040
1415 Start  1048
I-frame     1056 -> 1419 byte12 0x20
P-frame     1064 -> 1419 byte12 0x28
audio       1072 -> 1419 byte12 0x30
1415 Stop   1080
1360        1088
```

All 65,536-byte fragments of one source frame retain the **same** header-byte-12 value.
The next source video/audio frame consumes the next global sequence value.

This group byte is why the generic DVRIP header model now preserves bytes 12 and 13 rather
than discarding them as reserved. Existing callers retain exact previous behavior because
both fields default to zero.

## Validation

The implementation has deterministic unit vectors for:

- bootstrap AES
- AVTalk JSON
- FC/FD/FA media headers
- packed calendar timestamp
- native media-group sequence arithmetic
- protocol-dependent DVRIP header bytes

The integration test uses a loopback camera simulator with a generated RSA keypair and
verifies the complete two-socket exchange, including RSA decrypting the three encrypted
login fields, session AES, shared command sequencing, 64 KiB media fragmentation, sequence-0
1419 traffic, fragment-group tags, Stop, and the post-Stop capability refresh.

Actual video appearing on a physical AVTalk-screen camera remains the final hardware-level
validation; the implementation does not contain or depend on the proprietary FunSDK.
