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
- Change device password — **live-confirmed working 2026-07-07** via
  `ChangeRandomUser` (msg 1660/1661, session-less), against the real `xkfu`
  account on an already-provisioned camera: old password rejected afterward,
  new password authenticates. Two other reverse-engineered candidates
  (`ModifyPassword` alone; `ModifyPassword` + a `System.ExUserMap`
  read-modify-write with the documented `u()` obfuscation) were tried first
  and confirmed NOT to work despite both ACKing `Ret:100` — see
  PASSWORD_CHANGE_RE.md for the full comparison table; neither is used by the
  app. `changePassword` verifies by re-login before persisting regardless.
  The device still has an unremovable blank-`admin` LAN backdoor
  (SECURITY.md) regardless of any account's password.
- BLE pairing credential setting (ChangeRandomUser, msg 1660) — same client
  (`ChangeRandomUserClient`) as device-management password change above; ready
  for the fresh-pairing case, reliant on capturing the provisioning ACK there
  specifically (BLE now requests the fastest connection interval to improve
  capture).
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
- Recorded-clip browser (`OPFileQuery`, msg 1440) — **live-confirmed 2026-07-09**.
  Request MUST include `"Event":"*"` or the camera returns `Ret:119` with no
  list. Response is an `OPFileQuery` array of BeginTime/EndTime/FileName/
  FileLength (FileLength is ~KB blocks, not bytes).
- Recorded-video **playback/download — SOLVED, live-confirmed 2026-07-09.** The
  media-byte "gap" was a wrong codec guess, not a real blocker: recorded clips
  are **HEVC/H.265**, not H.264 (the old analysis searched for H.264 start codes
  and found none). Sequence (per OpenIPC/python-dvr): `OPPlayBack` **Claim on msg
  1424**, then **DownloadStart on msg 1420**; file bytes stream on **msg 1426**
  until a zero-length frame terminates; `DownloadStop` on 1420. The downloaded
  `.h264` file is HEVC wrapped in XM private NALs (`00 00 01` + marker
  `F9/FA/FC/FD` = reserved HEVC types 124–126 that decoders ignore); the real
  NALs are VPS(32)/SPS(33)/PPS(34)/IDR(19)/TRAIL(0,1). Strip the wrapper NALs →
  clean HEVC that decodes directly (confirmed: pulled a clip, decoded to MP4,
  correct scene + OSD timestamp). Sensor is 2304×2592 (stacked dual-view).
  In-app: `video/RecordedClipExporter` downloads + remuxes to MP4 (MediaMuxer)
  for ExoPlayer. Repro tooling: `tools/live/sdcard_probe.py`, `sdcard_download.py`.

## Key Design Patterns
- **Race-safe request/response**: Subscribe to DvripTransport.incomingFrames BEFORE sending (matches DvripLoginNegotiator)
- **Generic JSON editing**: EditableJson tree model covers all named configs without per-config UI
- **Post-BLE-provision credential setting**: ChangeRandomUserClient standalone, no prior session needed
- **Single shared session per camera**: `CameraSessionRegistry` (app-scoped)
  owns one `CameraSessionManager` per `host:port`, reference-counted across the
  live-view and device-management screen families with a short linger before
  teardown, and a per-camera `LoginRateLimiter` that survives manager rebuilds
  and caps total logins in a rolling window (a cheap backstop against any code
  path looping logins unboundedly). The whole app authenticates as rarely as
  possible and never in the background.
- **Update 2026-07-07**: `LoginRateLimiter` used to also enforce a minimum
  spacing between individual logins (added when Ret:205 looked burst-sensitive
  -- a handful of logins in a few seconds tripping it even though that's tiny
  over any longer window). **Live-confirmed** (not just theorized): the
  `admin`/blank backdoor hits Ret:205 purely from elapsed time since
  provisioning, with no real usage from the app or user in between -- see
  `[[project-icsee-password-change]]`. The spacing guard was solving a problem
  that wasn't causing the originally-reported symptom for that account, so
  it's been removed for `admin`. **Caveat: `Ret:205` is very likely a generic,
  reused error code**, not a single cause -- confirming it fires from pure
  time-elapsed for the backdoor account does NOT rule out a genuine
  login-rate lockout also existing (possibly for other accounts, or under
  different conditions). This is why the rolling-window count backstop was
  kept rather than removing rate limiting entirely.

## Auth-rate reduction — open investigation
- **`AdminToken` (login response, msg 1001)**: captured onto `AuthenticatedSession`
  (`adminToken`) but not yet used. **Open question:** does presenting it permit
  token-based *session resumption* on a fresh TCP connection instead of a full
  password login? Needs a live camera to probe (send a resumed-session frame
  carrying the token on a new socket and see whether commands are accepted
  without a msg-1000 login). Not evidenced yet either way. Note this only
  helps if a genuine login-rate lockout exists separately from the confirmed
  `admin`-expiry cause above (see caveat).
- **Discovery no longer authenticates**: the subnet sweep discriminator is now a
  msg-1010 pre-login negotiate (answered by msg 1011 with no login), not a msg-1000
  login, and already-saved camera IPs are skipped entirely.
