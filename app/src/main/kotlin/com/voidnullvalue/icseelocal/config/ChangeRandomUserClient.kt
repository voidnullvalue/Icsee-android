package com.voidnullvalue.icseelocal.config

import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Sends `ChangeRandomUser` (msgid 1660/1661) -- the command a freshly-paired
 * camera accepts to replace its factory-assigned random username/password
 * with a user-chosen login, **without any prior DVRIP login/session**
 * (mirrors the vendor's `SetDevPsdActivity`, which sends this via
 * `FunSDK.DevConfigJsonNotLoginPtl` -- note the "NotLogin" in that method
 * name, and the resulting JSON body has no `SessionID` field, unlike every
 * other named command in this app). This is the natural point to offer a
 * custom login: right after BLE provisioning hands back the camera's
 * assigned `RandomName`/`RandomPwd` in [com.voidnullvalue.icseelocal.ble.BlePairedCamera].
 *
 * Not yet exercised against a real camera that reports genuinely random
 * (non-`admin`) provisioning credentials -- see PROTOCOL_STATUS.md. Body
 * shape is taken directly from the decompiled vendor source, not guessed.
 */
object ChangeRandomUserClient {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val REQUEST_MESSAGE_ID = 1660
    private const val RESPONSE_MESSAGE_ID = 1661

    sealed class Result {
        data object Success : Result()
        data class Failure(val ret: Int?, val detail: String? = null) : Result()
    }

    suspend fun changeUser(
        host: String,
        port: Int,
        currentRandomName: String,
        currentRandomPassword: String,
        newUsername: String,
        newPassword: String,
        connectTimeoutMillis: Long = 8000,
        responseTimeoutMillis: Long = 8000,
    ): Result {
        val transport = DvripTransport(host, port, connectTimeoutMillis = connectTimeoutMillis.toInt())
        return try {
            transport.connect()
            val requestJson = buildJsonObject {
                put("Name", "ChangeRandomUser")
                put(
                    "ChangeRandomUser",
                    buildJsonObject {
                        put("RandomName", currentRandomName)
                        put("RandomPwd", currentRandomPassword)
                        put("NewName", newUsername)
                        put("NewPwd", newPassword)
                    },
                )
            }.toString()

            val responseText = withTimeoutOrNull(responseTimeoutMillis) {
                coroutineScope {
                    val responseDeferred = async(start = CoroutineStart.UNDISPATCHED) {
                        transport.incomingFrames.filter { it.header.messageId == RESPONSE_MESSAGE_ID }.first()
                    }
                    transport.send(session = 0u, messageId = REQUEST_MESSAGE_ID, payload = DvripPayloads.encodeJson(requestJson))
                    val frame = responseDeferred.await()
                    DvripPayloads.decodeJsonOrNull(frame.payload)
                }
            } ?: return Result.Failure(ret = null, detail = "timeout waiting for response")

            val obj = runCatching { json.parseToJsonElement(responseText).jsonObject }.getOrNull()
                ?: return Result.Failure(ret = null, detail = responseText)
            val ret = obj["Ret"]?.jsonPrimitive?.content?.toIntOrNull()
            if (ret == 100) Result.Success else Result.Failure(ret, responseText)
        } catch (e: Exception) {
            Result.Failure(ret = null, detail = e.message)
        } finally {
            transport.close()
        }
    }
}
