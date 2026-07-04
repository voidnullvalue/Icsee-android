package com.voidnullvalue.icseelocal.config

/**
 * Human-readable documentation for DVRIP config fields, keyed by the field's
 * leaf key (e.g. "PictureFlip"). The generic [JsonEditor] shows raw protocol
 * keys like `BLCMode` or bare booleans with no explanation; this turns those
 * into a friendly label + one-line description, and (only where the meaning is
 * unambiguous) decodes the stored value.
 *
 * Labels/descriptions are curated from the vendor app's own setting screens
 * and general Xiongmai domain knowledge. Value decodings are intentionally
 * limited to cases that are certain (on/off hex flags); ambiguous multi-value
 * enums get a label + description but keep their raw value, rather than risk a
 * wrong mapping.
 */
data class FieldDoc(
    val label: String,
    val description: String,
    /** Raw stored value -> human meaning, when unambiguous. Empty otherwise. */
    val values: Map<String, String> = emptyMap(),
)

object ConfigFieldDocs {
    /** On/off decoding shared by the many `"0x00000000"`/`"0x00000001"` flag fields. */
    private val ONOFF = mapOf("0x00000000" to "Off", "0x00000001" to "On", "0" to "Off", "1" to "On")

    private val docs: Map<String, FieldDoc> = mapOf(
        // --- Camera.Param (image / sensor) ---
        "PictureFlip" to FieldDoc("Flip image vertically", "Turns the picture upside-down — use when the camera is ceiling-mounted.", ONOFF),
        "PictureMirror" to FieldDoc("Mirror image horizontally", "Flips the picture left-to-right.", ONOFF),
        "RejectFlicker" to FieldDoc("Anti-flicker", "Reduces flicker caused by mains lighting (50/60 Hz).", ONOFF),
        "BLCMode" to FieldDoc("Backlight compensation", "Brightens a dark subject standing in front of a bright background."),
        "DayNightColor" to FieldDoc("Day / night colour", "Colour picture by day, switching to black-and-white at night."),
        "WhiteBalance" to FieldDoc("White balance", "How the camera corrects colour under different lighting."),
        "AeSensitivity" to FieldDoc("Auto-exposure sensitivity", "How quickly exposure reacts to changing light (higher = faster)."),
        "DncThr" to FieldDoc("Day/night threshold", "Light level at which the camera flips between colour and IR night mode."),
        "Day_nfLevel" to FieldDoc("Daytime noise reduction", "Smooths grain in the daytime image."),
        "Night_nfLevel" to FieldDoc("Night-time noise reduction", "Smooths grain in the night image."),
        "ElecLevel" to FieldDoc("Exposure target", "Overall brightness the auto-exposure aims for."),
        "IRCUTMode" to FieldDoc("IR-cut filter mode", "Controls the mechanical infrared-cut filter."),
        "AutoGain" to FieldDoc("Auto gain", "Automatically boosts the signal in low light (brighter, but noisier)."),
        "Gain" to FieldDoc("Gain level", "Manual signal boost, 0-100."),

        // --- Camera.ParamEx ---
        "SwitchMode" to FieldDoc(
            "Day / night mode",
            "0 = Auto, 1 = Day (colour), 2 = Night (B&W), 3 = Scheduled by time.",
            mapOf("0" to "Auto", "1" to "Day (colour)", "2" to "Night (B&W)", "3" to "Scheduled"),
        ),
        "KeepDayPeriod" to FieldDoc("Daytime schedule", "When 'Scheduled' is selected, the daily colour-mode window."),
        "NightEnhance" to FieldDoc("Night enhancement", "Extra brightening of the night image.", ONOFF),
        "LowLuxMode" to FieldDoc("Low-light mode", "Slower shutter for a brighter picture in very low light.", ONOFF),

        // --- Detect.MotionDetect ---
        "Enable" to FieldDoc("Motion detection", "Detect movement in the scene."),
        "Level" to FieldDoc("Motion sensitivity", "Higher values trigger on smaller/slower movement."),
        "RecordEnable" to FieldDoc("Record on motion", "Start recording to the SD card when motion is detected."),
        "SnapEnable" to FieldDoc("Snapshot on motion", "Capture a still image when motion is detected."),
        "MessageEnable" to FieldDoc("Push notification", "Send an alert when motion is detected."),
        "PtzEnable" to FieldDoc("PTZ on motion", "Move the camera to a preset when motion is detected."),
        "AlarmOutEnable" to FieldDoc("Alarm output", "Fire the wired alarm output on motion."),
        "BeepEnable" to FieldDoc("Buzzer", "Sound the camera's buzzer on motion."),
        "MailEnable" to FieldDoc("Email alert", "Email a notification on motion (requires mail setup)."),
        "FTPEnable" to FieldDoc("FTP upload", "Upload a clip/snapshot to an FTP server on motion."),
        "RecordLatch" to FieldDoc("Post-motion record (s)", "How many seconds to keep recording after motion stops."),
        "AlarmOutLatch" to FieldDoc("Alarm output duration (s)", "How long the alarm output stays active."),
        "EventLatch" to FieldDoc("Event hold (s)", "Minimum gap before a new motion event is reported."),

        // --- General.General ---
        "AutoLogout" to FieldDoc("Auto-logout (min)", "Log the local menu out after this many minutes (0 = never)."),
        "FontSize" to FieldDoc("OSD font size", "Size of the on-screen overlay text."),

        // --- NetWork.NetCommon ---
        "HttpPort" to FieldDoc("HTTP port", "Port for the camera's web interface."),
        "TCPPort" to FieldDoc("DVRIP (TCP) port", "Main control/media port this app connects to (default 34567)."),
        "SSLPort" to FieldDoc("SSL port", "Port for TLS connections, if supported."),
        "HostName" to FieldDoc("Device name", "The camera's network host name."),
        "MAC" to FieldDoc("MAC address", "The camera's hardware network address (read-only)."),
    )

    fun forKey(key: String): FieldDoc? = docs[key]
}
