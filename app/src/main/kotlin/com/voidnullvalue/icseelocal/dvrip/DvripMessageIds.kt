package com.voidnullvalue.icseelocal.dvrip

/**
 * Message id catalog. See PROTOCOL_NOTES.md for the pcap evidence behind
 * each entry -- ids marked "inferred" were seen on the wire but their
 * semantics come only from request/response ordering, not from readable
 * content (their payloads are encrypted).
 */
object DvripMessageIds {
    const val LOGIN_REQUEST = 1000
    const val LOGIN_RESPONSE = 1001

    const val KEEPALIVE_REQUEST = 1006
    /** Inferred from ordering only -- content is encrypted, never independently confirmed. */
    const val KEEPALIVE_RESPONSE = 1007

    /** Per task brief; never observed in the capture (see PROTOCOL_NOTES.md "Login"). */
    const val PRE_LOGIN_NEGOTIATE_REQUEST = 1010
    const val PRE_LOGIN_NEGOTIATE_RESPONSE = 1011

    const val PTZ_CONTROL_REQUEST = 1400
    /** Inferred from ordering; Ret value not independently confirmed (encrypted). */
    const val PTZ_CONTROL_RESPONSE = 1401

    /** OPMonitor claim/start request. Confirmed plaintext JSON in capture. */
    const val MONITOR_REQUEST = 1413
    /** OPMonitor claim/start response. Confirmed plaintext JSON in capture. */
    const val MONITOR_RESPONSE = 1414

    /** Combined video+audio media stream, chunked at up to 8192 payload bytes. */
    const val MEDIA_STREAM = 1412

    /**
     * OPTalk lifecycle control (`{"Name":"OPTalk","OPTalk":{"Action":...}}`),
     * sent on the *control* connection. `Action:"Start"` is the step that
     * actually opens the camera's speaker for upstream audio -- LIVE-CONFIRMED
     * 2026-07-02: the [TALK_CLAIM_REQUEST] claim alone returns Ret:100 but plays
     * nothing; a plaintext OPTalk `Start` here returns Ret:100 with the camera's
     * expected AudioFormat and the speaker then plays the streamed frames.
     * `Action:"Stop"` releases it. Response comes back on [OPTALK_CONTROL_RESPONSE].
     */
    const val OPTALK_CONTROL_REQUEST = 1430

    const val TALK_CLAIM_REQUEST = 1434
    const val TALK_CLAIM_RESPONSE = 1435
    /** OPTalk control response (Ret for [OPTALK_CONTROL_REQUEST]); also a talk teardown ack. */
    const val OPTALK_CONTROL_RESPONSE = 1431
    /** Also observed as a talk teardown acknowledgement. */
    const val TALK_ACK = 1431
    const val TALK_AUDIO_UPSTREAM = 1432
    const val TALK_AUDIO_DOWNSTREAM = 1433

    /** Client's UDP discovery probe (zero-length payload), broadcast to 255.255.255.255:34569. */
    const val DISCOVERY_PROBE = 1530

    /**
     * Generic named-config commands, confirmed live against a real camera on
     * 2026-07-03 (see PROTOCOL_NOTES.md "Generic config get/set -- LIVE
     * CONFIRMED"). Every request shares the envelope
     * `{"Name":"<config>","<config>":<value>,"SessionID":"0x..."}`; only the
     * message id and the meaning of `<config>` change. These four ids sort
     * named configs into distinct catalogs -- e.g. `Camera.Param` only
     * responds on [CONFIG_GET]/[CONFIG_SET], while `SystemInfo` only responds
     * on [INFO_GET] -- confirmed by testing each name against each id live,
     * not assumed. Sourced from the decompiled vendor app's
     * `com.lib.sdk.bean.JsonConfig`, which annotates every config name
     * constant with its `cmdId`; cross-checked against [OPTALK_CONTROL_REQUEST]
     * (`OPTalk` in that file also lists 1430, matching our own independently
     * pcap-confirmed value) before trusting the rest of the file.
     */
    /** Read-only device/system info (SystemInfo, StorageInfo, 4GInfo, EncyptChipInfo). Response on [INFO_GET_RESPONSE]. */
    const val INFO_GET = 1020
    const val INFO_GET_RESPONSE = 1021

    /**
     * Per-channel named config write (Camera.Param, Detect.MotionDetect,
     * General.General, NetWork.NetCommon, Record, ModifyPassword, ...).
     * Live-confirmed by round-tripping General.General's exact current
     * values back through this id and observing `Ret:100`.
     */
    const val CONFIG_SET = 1040
    const val CONFIG_SET_RESPONSE = 1041

    /** Per-channel named config read, counterpart to [CONFIG_SET]. Response on [CONFIG_GET_RESPONSE]. */
    const val CONFIG_GET = 1042
    const val CONFIG_GET_RESPONSE = 1043

    /** Read-only capability/ability queries (EncodeCapability, SupportExtRecord, FishEyePlatform, ...). */
    const val ABILITY_GET = 1360
    const val ABILITY_GET_RESPONSE = 1361

    /** Current device time as a plain string (`"YYYY-MM-DD HH:MM:SS"`), no per-config nesting. Live-confirmed. */
    const val TIME_QUERY = 1452

    /** Reboot/shutdown ("OPMachine" with an `Action` field). See PROTOCOL_NOTES.md for the confirmed/attempted action shapes. */
    const val MACHINE_CONTROL = 1450

    /** SD card management (info/format). "OPStorageManager". */
    const val STORAGE_MANAGER = 1460

    /** Recorded-file browsing: OPPlayBack (playback control), OPFileQuery (file listing), OPSCalendar (recording calendar). */
    const val PLAYBACK_CONTROL = 1420
    const val FILE_QUERY = 1440
    const val CALENDAR_QUERY = 1446

    /** OSD overlay text, "in-place" variant that doesn't persist to config (OPSetOSD). */
    const val SET_OSD = 1656

    /**
     * Account management. `GetAllUser` (empty body) returns `{"Users":[...]}`
     * with each account's full object (Name, Group, AuthorityList, Password
     * hash, PasswordV2, ...). `ModifyUser` submits a full user object with a
     * changed field back. Both live-confirmed 2026-07-03 against a real
     * camera: renaming the admin account via [USER_MODIFY] took effect (the
     * new name appeared in a follow-up [USER_GET_ALL] and login succeeded
     * under it). Sourced from the vendor app's `AboutDevModifyPwdActivity`
     * (`FunSDK.DevCmdGeneral(..., 1472, "GetAllUser", ...)` and
     * `..., 1484, "ModifyUser", ...`).
     */
    const val USER_GET_ALL = 1472
    const val USER_GET_ALL_RESPONSE = 1473
    const val USER_MODIFY = 1484
    const val USER_MODIFY_RESPONSE = 1485

    /**
     * Message ids the task brief advertises as not passing through the
     * generic post-login AES envelope. Note this does *not* mean their
     * bytes are unencrypted plaintext in every case: message 1000's payload
     * is itself a base64-encoded ~432-byte encrypted blob in the capture,
     * but that encryption is performed by the login flow itself (RSA/AES
     * credential wrapping), not by [com.voidnullvalue.icseelocal.crypto.SessionCrypto]'s
     * generic per-message envelope. `shouldEncrypt()` on that interface is
     * about the generic envelope only.
     */
    val TRANSPORT_UNENCRYPTED_IDS: Set<Int> = setOf(
        1000, 1001, 1008, 1009, 1010, 1011,
        1050, 1054,
        1412, 1413, 1414,
        1422, 1424, 1425, 1426,
        1432, 1433, 1434, 1435,
        1449, 1522, 1572, 1576, 1580, 1582,
        1645, 2062, 2063, 2123, 2140, 3016, 3502,
    )
}
