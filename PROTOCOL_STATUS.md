# DVRIP Protocol Implementation Status

This document tracks which protocol features have been live-confirmed against a real camera (192.168.88.129:34567 on 2026-07-03) and which are inferred from decompiled vendor source.

## Generic Config Get/Set (DvripConfigChannel)

### LIVE-CONFIRMED on 2026-07-03
- **Message IDs**: INFO_GET (1020), INFO_GET_RESPONSE (1021), CONFIG_GET (1042), CONFIG_GET_RESPONSE (1043), CONFIG_SET (1040), CONFIG_SET_RESPONSE (1041), ABILITY_GET (1360), ABILITY_GET_RESPONSE (1361)
- **Envelope format**: `{"Name":"<config>","<config>":<value>,"SessionID":"0x..."}`
- **Response format**: `{"Name":"<config>","<config>":<value>,"Ret":100,"SessionID":"0x..."}`
- **Info catalogs**: `SystemInfo`, `StorageInfo` respond with Ret:100 and data
- **Config catalogs**: Camera.Param, Camera.ParamEx, General.General, Detect.MotionDetect, NetWork.NetCommon, Record
- **Write round-trip**: General.General's own response values back → Ret:100 confirmed

### INFERRED FROM VENDOR SOURCE (not yet live-confirmed)
- **OPTimeQuery** (1452): Returns `{"OPTimeQuery":"2026-07-03 05:04:51"}` (no per-config envelope)
- **OPMachine** (1450): Reboot command structure confirmed in vendor, not sent live
- **ModifyPassword** (1040): Shape confirmed in DevPsdManageActivity, response never received live
- **ChangeRandomUser** (1660/1661): No SessionID, shape from SetDevPsdActivity; never sent to real camera

### LIVE-CONFIRMED on 2026-07-07
- **GetRandomUser** (1660/1661, same message IDs as ChangeRandomUser — dispatched by JSON
  `Name` not a distinct msgid): returns `{"GetRandomUser":{"Info":"<base64>"}}` (or `InfoUser`
  on some firmware). `Info` decrypts with AES-128-CBC, zero IV, key =
  `SerialNo[5:11]+SerialNo[1:7]+SerialNo[8:12]` to `"p1:<user> p2:<pass> t:<token>"` — this is
  the real (non-backdoor) provisioned account's plaintext password, recoverable independent of
  BLE ACK capture. See PROTOCOL_NOTES.md "Recovering the real provisioned account" and
  `[[project-icsee-random-user-decryption]]`. An earlier XOR-based attempt against
  `System.ExUserMap`'s `PasswordV2` field was wrong (circular derivation) and has been removed.

## Tier 1: Device Management (Complete)
- Device info screen (SystemInfo)
- Time query (OPTimeQuery)
- Reboot device (OPMachine)
- Username change (`ModifyUser`, msg 1484) — **live-confirmed**: rename applies,
  re-login under the new name succeeds. User list via `GetAllUser` (msg 1472).
- Change device password — **implemented, not yet live-verified.** Both
  reverse-engineered steps are wired up: plaintext `ModifyPassword` (msg 1040)
  + a `System.ExUserMap` read-modify-write whose Password uses the vendor
  `u()` obfuscation = `"0001"+base64(pw)` with the first two chars swapped
  (see PASSWORD_CHANGE_RE.md, `XiongmaiCrypto.obfuscateExUserMapPassword`).
  An earlier version only sent `ModifyPassword`, predating discovery of the
  `ExUserMap` step, and reliably failed to actually change login. The device
  still has an unremovable blank-`admin` LAN backdoor (SECURITY.md) regardless
  of any account's password.
- BLE pairing credential setting (ChangeRandomUser, msg 1660) — client ready;
  reliant on capturing the provisioning ACK (BLE now requests the fastest
  connection interval to improve capture).
- Real-account credential retrieval (GetRandomUser, msg 1660) — **live-confirmed**,
  independent of ACK capture; used both right after BLE pairing and on-demand from the
  camera settings screen ("Retrieve Credentials").

## Tier 2: Advanced Settings (Complete via Generic Editor)
- Image settings (Camera.Param / Camera.ParamEx) — plus a **friendly Image
  settings screen** (flip/mirror/gain/day-night as real controls).
- Motion detection (Detect.MotionDetect)
- Recording config (Record / ExtRecord)
- **Field documentation layer**: cryptic keys shown with friendly labels +
  descriptions + decoded on/off values in the generic editor.

## Tier 3: Advanced Operations
- PTZ presets (`OPPTZControl` Set/Goto/Clear, msg 1400) — **live-confirmed** `Ret:100`.
- SD card format (`OPStorageManager`, msg 1460, `Action:Clear/Type:Data`) —
  built from decompiled spec, confirm-gated; **not yet run live** (destructive).
- Recorded-clip browser (`OPFileQuery`, msg 1440) — built from decompiled spec;
  response parsing **not yet confirmed** against a live reply. Recorded-video
  *playback* is blocked by the same DVRIP media-byte gap as live view (below).

## Key Design Patterns
- **Race-safe request/response**: Subscribe to DvripTransport.incomingFrames BEFORE sending (matches DvripLoginNegotiator)
- **Generic JSON editing**: EditableJson tree model covers all named configs without per-config UI
- **Post-BLE-provision credential setting**: ChangeRandomUserClient standalone, no prior session needed
- **Single shared, rate-limited session per camera**: `CameraSessionRegistry`
  (app-scoped) owns one `CameraSessionManager` per `host:port`, reference-counted
  across the live-view and device-management screen families with a short linger
  before teardown, and a per-camera `LoginRateLimiter` that survives manager
  rebuilds. This firmware counts login *rate* toward its Ret:205 lockout, so the
  whole app authenticates as rarely as possible and never in the background.

## Auth-rate reduction — open investigation
- **`AdminToken` (login response, msg 1001)**: captured onto `AuthenticatedSession`
  (`adminToken`) but not yet used. **Open question:** does presenting it permit
  token-based *session resumption* on a fresh TCP connection instead of a full
  password login? If so, the unavoidable socket-death reconnects could stop
  counting against the Ret:205 login-rate budget — the single biggest remaining
  lever. Needs a live camera to probe (send a resumed-session frame carrying the
  token on a new socket and see whether commands are accepted without a msg-1000
  login). Not evidenced yet either way.
- **Discovery no longer authenticates**: the subnet sweep discriminator is now a
  msg-1010 pre-login negotiate (answered by msg 1011 with no login), not a msg-1000
  login, and already-saved camera IPs are skipped entirely.
