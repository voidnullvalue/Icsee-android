package com.voidnullvalue.icseelocal.config

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `SystemInfo` config, read via [DvripConfigChannel.getInfo]. Field shape
 * live-confirmed against a real camera on 2026-07-03:
 *
 * ```
 * {"AlarmInChannel":0,"AlarmOutChannel":1,"AudioInChannel":1,
 *  "BuildTime":"2024-07-20 15:41:16","DeviceModel":"X6-WEQ-V2",
 *  "DeviceRunTime":"0x00084a02","HardWare":"XM530V200_X6-WEQ-V2_8M",
 *  "HardWareVersion":"1.01","Pid":"A9A022913235C00M",
 *  "SerialNo":"a44d13007be81c4d",
 *  "SoftWareVersion":"V5.11.R02.000809V1.10010.346837.0000010",
 *  "VideoInChannel":1, ...}
 * ```
 *
 * `DeviceRunTime` is a hex-encoded seconds-since-boot uptime counter.
 */
data class SystemInfo(
    val deviceModel: String,
    val hardware: String,
    val hardwareVersion: String,
    val softwareVersion: String,
    val serialNo: String,
    val pid: String,
    val buildTime: String,
    val deviceRunTimeSeconds: Long,
    val videoInChannel: Int,
    val audioInChannel: Int,
) {
    companion object {
        fun fromJson(value: kotlinx.serialization.json.JsonElement): SystemInfo? {
            val obj = runCatching { value.jsonObject }.getOrNull() ?: return null
            fun str(key: String) = obj[key]?.jsonPrimitive?.content ?: ""
            fun int(key: String) = obj[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val runtimeHex = str("DeviceRunTime").removePrefix("0x").removePrefix("0X")
            val runtimeSeconds = runtimeHex.toLongOrNull(16) ?: 0L
            return SystemInfo(
                deviceModel = str("DeviceModel"),
                hardware = str("HardWare"),
                hardwareVersion = str("HardWareVersion"),
                softwareVersion = str("SoftWareVersion"),
                serialNo = str("SerialNo"),
                pid = str("Pid"),
                buildTime = str("BuildTime"),
                deviceRunTimeSeconds = runtimeSeconds,
                videoInChannel = int("VideoInChannel"),
                audioInChannel = int("AudioInChannel"),
            )
        }
    }
}
