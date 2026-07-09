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
        "PictureFlip" to FieldDoc("Flip image vertically", "Turns the picture upside-down. Use when camera is ceiling-mounted. (0=Off, 1=On)", ONOFF),
        "PictureMirror" to FieldDoc("Mirror image horizontally", "Flips picture left-to-right. (0=Off, 1=On)", ONOFF),
        "RejectFlicker" to FieldDoc("Anti-flicker", "Reduces flicker from mains lighting (50/60 Hz). (0=Off, 1=On)", ONOFF),
        "BLCMode" to FieldDoc(
            "Backlight compensation",
            "Brightens dark subjects with bright backgrounds. 0=Off, 1=Low, 2=Mid, 3=High.",
            mapOf("0x00000000" to "Off", "0x00000001" to "Low", "0x00000002" to "Mid", "0x00000003" to "High"),
        ),
        "DayNightColor" to FieldDoc(
            "Day / night mode (legacy)",
            "0=Off, 1=On (auto), 2=Auto (colour to B&W), 3=Auto (full range). See SwitchMode for preferred setting.",
            mapOf("0x00000000" to "Off", "0x00000001" to "On (auto)", "0x00000002" to "Auto day→night", "0x00000003" to "Auto full"),
        ),
        "WhiteBalance" to FieldDoc(
            "White balance",
            "How the camera corrects colour under different lighting. 0=Auto, 1=Sunny, 2=Cloudy, 3=Tungsten, 4=Fluorescent, 5=Manual.",
            mapOf("0x00000000" to "Auto", "0x00000001" to "Sunny", "0x00000002" to "Cloudy", "0x00000003" to "Tungsten", "0x00000004" to "Fluorescent", "0x00000005" to "Manual"),
        ),
        "AeSensitivity" to FieldDoc("Auto-exposure response", "How quickly exposure reacts to changing light. Range 0-15 (0=slow, 15=fast)."),
        "DncThr" to FieldDoc("Day/night switch threshold", "Light level (0-100) at which camera switches between colour and IR night mode."),
        "Day_nfLevel" to FieldDoc("Daytime noise reduction", "Grain smoothing in daylight. Range 0-10 (0=none, 10=maximum)."),
        "Night_nfLevel" to FieldDoc("Night-time noise reduction", "Grain smoothing in night mode. Range 0-10 (0=none, 10=maximum)."),
        "ElecLevel" to FieldDoc("Exposure target", "Target brightness (0-100) the auto-exposure aims for."),
        "IRCUTMode" to FieldDoc(
            "IR-cut filter mode",
            "Controls mechanical IR-cut filter. 0=Auto, 1=On (always pass IR), 2=Off (block IR).",
            mapOf("0x00000000" to "Auto", "0x00000001" to "On (pass IR)", "0x00000002" to "Off (block IR)"),
        ),
        "AutoGain" to FieldDoc("Auto gain", "Automatically boost signal in low light (0=Off, 1=On). Brighter but noisier.", ONOFF),
        "Gain" to FieldDoc("Gain level", "Manual signal boost. Range 0-100 (0=minimum, 100=maximum)."),

        // --- Camera.ParamEx ---
        "SwitchMode" to FieldDoc(
            "Day / night mode",
            "Preferred setting for colour/B&W switching. 0=Auto, 1=Day (colour only), 2=Night (B&W only), 3=Scheduled.",
            mapOf("0" to "Auto", "1" to "Day (colour)", "2" to "Night (B&W)", "3" to "Scheduled"),
        ),
        "KeepDayPeriod" to FieldDoc("Daytime schedule", "When 'Scheduled' mode is active, the daily window to stay in colour (e.g. '07:00:00-18:00:00')."),
        "NightEnhance" to FieldDoc("Night enhancement", "Extra brightening in night mode (0=Off, 1=On). Makes dark scenes brighter.", ONOFF),
        "LowLuxMode" to FieldDoc("Low-light mode", "Slower shutter for brighter picture in very dim light (0=Off, 1=On).", ONOFF),

        // --- Detect.MotionDetect ---
        "Enable" to FieldDoc("Motion detection", "Enable/disable motion detection in the scene."),
        "Level" to FieldDoc("Motion sensitivity", "Sensitivity range 0-10. Higher values trigger on smaller/slower movement."),
        "RecordEnable" to FieldDoc("Record on motion", "Start recording to SD card when motion is detected (0=Off, 1=On)."),
        "SnapEnable" to FieldDoc("Snapshot on motion", "Capture still image when motion is detected (0=Off, 1=On)."),
        "MessageEnable" to FieldDoc("Push notification", "Send alert when motion is detected (0=Off, 1=On)."),
        "PtzEnable" to FieldDoc("PTZ on motion", "Move camera to preset when motion is detected (0=Off, 1=On)."),
        "AlarmOutEnable" to FieldDoc("Alarm output", "Fire wired alarm output on motion (0=Off, 1=On)."),
        "BeepEnable" to FieldDoc("Buzzer", "Sound camera's buzzer on motion (0=Off, 1=On)."),
        "MailEnable" to FieldDoc("Email alert", "Email notification on motion; requires mail server setup (0=Off, 1=On)."),
        "FTPEnable" to FieldDoc("FTP upload", "Upload clip/snapshot to FTP server on motion (0=Off, 1=On)."),
        "RecordLatch" to FieldDoc("Post-motion record (s)", "Seconds to keep recording after motion stops. Range 0-300, typical 10."),
        "AlarmOutLatch" to FieldDoc("Alarm output duration (s)", "Seconds the wired alarm output stays active. Range 0-300, typical 10."),
        "EventLatch" to FieldDoc("Event hold (s)", "Minimum gap in seconds before a new motion event is reported. Range 0-300, typical 2-5."),

        // --- General.General ---
        "AutoLogout" to FieldDoc("Auto-logout (min)", "Minutes before local menu auto-logs out (0=never, 1-120=timeout)."),
        "FontSize" to FieldDoc("OSD font size", "On-screen overlay text size in pixels. Typical 12-32."),

        // --- NetWork.NetCommon ---
        "HttpPort" to FieldDoc("HTTP port", "Port for camera's web interface (default 80, range 1-65535)."),
        "TCPPort" to FieldDoc("DVRIP (TCP) port", "Main control/media port (default 34567, range 1-65535)."),
        "SSLPort" to FieldDoc("SSL port", "TLS port for secure connections (default 8443, range 1-65535)."),
        "HostName" to FieldDoc("Device name", "Camera's network hostname (alphanumeric + hyphen, max 32 chars)."),
        "MAC" to FieldDoc("MAC address", "Hardware network address (read-only, format: HH:HH:HH:HH:HH:HH)."),
        "DvrMac" to FieldDoc("MAC address", "Hardware network address (read-only)."),
        "GateWay" to FieldDoc("Gateway", "Your router's IP address (e.g. 192.168.1.1)."),
        "HostIP" to FieldDoc("IP address", "The camera's IP address on your network."),
        "Submask" to FieldDoc("Subnet mask", "Network mask, usually 255.255.255.0."),
        "MaxBps" to FieldDoc("Bandwidth cap", "Maximum upload bitrate the camera will use (kbps)."),
        "UseHSDownLoad" to FieldDoc("High-speed download", "Allow faster-than-realtime clip download (0=Off, 1=On).", ONOFF),
        "TransferPlan" to FieldDoc(
            "Streaming priority",
            "What the camera optimises the stream for. 0=Quality, 1=Balanced, 2=Realtime (lower latency).",
            mapOf("0" to "Quality", "1" to "Balanced", "2" to "Realtime"),
        ),

        // --- Record (recording schedule) ---
        "PreRecord" to FieldDoc("Pre-record (s)", "Seconds of video kept from *before* an event triggers. Range 0-30, typical 5."),
        "Redundancy" to FieldDoc("Redundant recording", "Write a second copy to a backup disk if present (0=Off, 1=On).", ONOFF),
        "RecordMode" to FieldDoc(
            "Record mode",
            "How recording is decided. \"ConfigDesc\" = follow this weekly schedule; \"ManualDesc\" = always record now; \"ClosedDesc\" = never record.",
            mapOf("ConfigDesc" to "By schedule", "ManualDesc" to "Always (manual)", "ClosedDesc" to "Off"),
        ),
        "PacketLength" to FieldDoc("Clip length (min)", "How long each recorded file is before a new one starts. Range 1-120, typical 5-30."),
        "Channel" to FieldDoc("Channel", "Video channel number. Single-lens cameras always use 0."),
        "Mask" to FieldDoc("Schedule mask", "Low-level per-slot on/off bitmap for the weekly schedule. Prefer the time-section view above; editing this raw is rarely needed."),
        "TimeSection" to FieldDoc(
            "Time section",
            "One scheduled window: \"<type> HH:MM:SS-HH:MM:SS\". The leading number is what to record — 0=nothing, 1=continuous, 2=on motion, 4=on alarm (add them, e.g. 6 = motion+alarm). Example: \"1 00:00:00-24:00:00\" records continuously all day.",
        ),
        "WorkSheet" to FieldDoc("Weekly schedule", "The per-day list of recording time sections (Sunday first)."),

        // --- General extras ---
        "OverWrite" to FieldDoc(
            "When card is full",
            "\"OverWrite\" loops and erases the oldest footage; \"KeepClose\" stops recording instead.",
            mapOf("OverWrite" to "Loop (erase oldest)", "KeepClose" to "Stop recording"),
        ),
        "LocalNo" to FieldDoc("Device number", "Remote-control address for this device (0-998). Leave as-is if unsure."),
        "MachineName" to FieldDoc("Device name", "Friendly name shown for this camera."),
        "VideoStandard" to FieldDoc(
            "Video standard",
            "Regional frame-rate standard. PAL (50 Hz regions) or NTSC (60 Hz regions).",
            mapOf("PAL" to "PAL (50 Hz)", "NTSC" to "NTSC (60 Hz)"),
        ),
        "AutoLogoutTime" to FieldDoc("Auto-logout (min)", "Minutes before the local menu auto-logs out (0=never)."),

        // --- OSD / overlay ---
        "TimeTitleAttribute" to FieldDoc("Show timestamp", "Overlay the date/time on the picture."),
        "ChannelTitleAttribute" to FieldDoc("Show camera name", "Overlay the channel/camera name on the picture."),
        "EncodeBlend" to FieldDoc("Overlay on recording", "Burn the timestamp/name into recorded video too, not just the live view."),

        // --- Detect extras ---
        "SendMail" to FieldDoc("Email alert", "Email a notification when this event fires (0=Off, 1=On).", ONOFF),
        "EventHandler" to FieldDoc("Event actions", "What the camera does when this detector triggers (record, snapshot, notify, …)."),
    )

    /**
     * A one-paragraph explanation shown at the top of the generic editor for a
     * whole config, so screens like the recording schedule aren't a wall of
     * unexplained fields. Keyed by config name (e.g. "Record").
     */
    private val configIntros: Map<String, String> = mapOf(
        "Record" to "Weekly recording schedule. Each day has up to six time sections; a section's leading number is what to record in that window — 0 = nothing, 1 = continuously, 2 = on motion, 4 = on alarm (add them together for combinations). \"RecordMode\" must be \"By schedule\" for this to apply.",
        "Detect.MotionDetect" to "Motion detection and what happens when motion is seen. Turn it on, set the sensitivity, then choose the actions (record, snapshot, push notification, buzzer, …).",
        "General.General" to "General device options: naming, auto-logout, what to do when the SD card fills up, and the regional video standard.",
        "NetWork.NetCommon" to "Core network settings — IP address, gateway, ports, and bandwidth. Change with care: a wrong value here can make the camera unreachable.",
    )

    fun forKey(key: String): FieldDoc? = docs[key]

    fun configIntro(configName: String): String? = configIntros[configName]

    /**
     * Turns a raw protocol key with no [FieldDoc] into something readable, so
     * unknown fields show "Pre Record" rather than "PreRecord". Splits camelCase
     * / PascalCase / snake_case and title-cases the words.
     */
    fun humanize(key: String): String {
        if (key.isBlank()) return key
        val spaced = key
            .replace('_', ' ')
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .trim()
        return spaced.split(' ').joinToString(" ") { w ->
            if (w.length > 1 && w.all { it.isUpperCase() }) w // keep acronyms (IP, MAC)
            else w.replaceFirstChar { it.uppercase() }
        }
    }
}
