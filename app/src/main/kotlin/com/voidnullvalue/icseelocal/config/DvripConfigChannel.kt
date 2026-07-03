package com.voidnullvalue.icseelocal.config

import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import com.voidnullvalue.icseelocal.session.DvripCommandChannel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Generic DVRIP named-config get/set channel -- **live-confirmed against a
 * real camera on 2026-07-03** (see PROTOCOL_NOTES.md "Generic config
 * get/set -- LIVE CONFIRMED"). Every request/response shares one envelope:
 *
 * ```
 * -> {"Name":"<config>","<config>":<value>,"SessionID":"0x..."}
 * <- {"Name":"<config>","<config>":<value>,"Ret":100,"SessionID":"0x..."}
 * ```
 *
 * Config names are split across independent catalogs, each gated to its own
 * message id -- confirmed by testing names against ids live, not assumed:
 * `SystemInfo`/`StorageInfo` only answer on [DvripMessageIds.INFO_GET];
 * `Camera.Param`/`Detect.MotionDetect`/`General.General`/`NetWork.NetCommon`/
 * `Record` only answer on [DvripMessageIds.CONFIG_GET] (and round-trip
 * through [DvripMessageIds.CONFIG_SET] -- confirmed by writing
 * `General.General`'s own current values back and observing `Ret:100`);
 * `EncodeCapability`/`SupportExtRecord` only answer on
 * [DvripMessageIds.ABILITY_GET]. Sending the wrong name/id pair doesn't
 * error loudly -- it comes back `Ret:103` or `Ret:607`, which is exactly
 * what misled earlier probing before this was mapped out (see
 * `com.lib.sdk.bean.JsonConfig` in the decompiled vendor app, which
 * annotates every config name with its `cmdId`).
 *
 * [DvripCommandChannel.sendJson] only sends a request -- it does not itself
 * wait for or return the camera's reply (`DvripCommandChannel`'s own doc
 * confirms it just applies the JSON/crypto envelope and forwards to
 * `DvripTransport.send`). So this class -- like [com.voidnullvalue.icseelocal.session.DvripLoginNegotiator]
 * -- subscribes to [DvripTransport.incomingFrames] filtered by the expected
 * response message id *before* sending, matching the race-avoidance pattern
 * used there, rather than trying to read a reply off the sent frame.
 */
class DvripConfigChannel(
    private val transport: DvripTransport,
    private val commandChannel: DvripCommandChannel,
    private val sessionId: UInt,
    private val responseTimeoutMillis: Long = 5000,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun sessionIdHex(): String = "0x%08x".format(sessionId.toLong())

    private fun buildEnvelope(name: String, value: JsonElement): String {
        val obj = buildJsonObject {
            put("Name", name)
            put(name, value)
            put("SessionID", sessionIdHex())
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private suspend fun request(requestMessageId: Int, responseMessageId: Int, name: String, value: JsonElement): ConfigResult {
        val result = withTimeoutOrNull(responseTimeoutMillis) {
            coroutineScope {
                // Subscribe before sending -- avoids the race where the response
                // arrives before we start collecting (see DvripLoginNegotiator).
                val responseDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                    transport.incomingFrames.filter { it.header.messageId == responseMessageId }.first()
                }
                commandChannel.sendJson(requestMessageId, buildEnvelope(name, value))
                responseDeferred.await()
            }
        } ?: return ConfigResult.Failure(name, ret = null, raw = "timeout waiting for response $responseMessageId")

        val text = commandChannel.decodeResponse(result) ?: return ConfigResult.Failure(name, ret = null, raw = null)
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ConfigResult.Failure(name, ret = null, raw = text)
        val ret = (obj["Ret"] as? JsonPrimitive)?.content?.toIntOrNull()
        val value = obj[name]
        return if (ret == 100 && value != null) {
            ConfigResult.Success(name, value)
        } else {
            ConfigResult.Failure(name, ret, text)
        }
    }

    /** Device/system info reads: `SystemInfo`, `StorageInfo`, `4GInfo`, `EncyptChipInfo`. */
    suspend fun getInfo(name: String): ConfigResult =
        request(DvripMessageIds.INFO_GET, DvripMessageIds.INFO_GET_RESPONSE, name, JsonObject(emptyMap()))

    /** Per-channel named config read: `Camera.Param`, `Detect.MotionDetect`, `General.General`, `NetWork.NetCommon`, `Record`. */
    suspend fun getConfig(name: String): ConfigResult =
        request(DvripMessageIds.CONFIG_GET, DvripMessageIds.CONFIG_GET_RESPONSE, name, JsonObject(emptyMap()))

    /**
     * Per-channel named config write. Pass back the (modified) [JsonElement]
     * previously returned by [getConfig] for the same `name` -- these config
     * values are frequently arrays-of-objects or deeply nested, and the
     * camera has only been confirmed to accept a full round-trip of its own
     * shape, not a partial/sparse update.
     */
    suspend fun setConfig(name: String, value: JsonElement): ConfigResult =
        request(DvripMessageIds.CONFIG_SET, DvripMessageIds.CONFIG_SET_RESPONSE, name, value)

    /** Read-only capability queries: `EncodeCapability`, `SupportExtRecord`, `FishEyePlatform`. */
    suspend fun getAbility(name: String): ConfigResult =
        request(DvripMessageIds.ABILITY_GET, DvripMessageIds.ABILITY_GET_RESPONSE, name, JsonObject(emptyMap()))
}

sealed class ConfigResult {
    data class Success(val name: String, val value: JsonElement) : ConfigResult()

    /** [ret] is null when the response couldn't be parsed/arrive at all (transport/timeout) rather than a device-reported code. */
    data class Failure(val name: String, val ret: Int?, val raw: String?) : ConfigResult()
}
