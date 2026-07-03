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

## Tier 1: Device Management (Complete)
- Device info screen (SystemInfo)
- Time query (OPTimeQuery)
- Reboot device (OPMachine)
- Change device password (ModifyPassword)
- BLE pairing credential setting (ChangeRandomUser) — code ready, needs BLE integration

## Tier 2: Advanced Settings (Complete via Generic Editor)
- Image settings (Camera.Param / Camera.ParamEx)
- Motion detection (Detect.MotionDetect)
- Recording config (Record / ExtRecord)

## Tier 3: Advanced Operations (Not Yet Implemented)
- SD card format (OPStorageManager)
- Playback (OPPlayBack / OPSCalendar / OPFileQuery)
- PTZ presets (OPPtzLocate)

## Key Design Patterns
- **Race-safe request/response**: Subscribe to DvripTransport.incomingFrames BEFORE sending (matches DvripLoginNegotiator)
- **Generic JSON editing**: EditableJson tree model covers all named configs without per-config UI
- **Post-BLE-provision credential setting**: ChangeRandomUserClient standalone, no prior session needed
