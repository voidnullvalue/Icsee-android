package com.voidnullvalue.icseelocal.config

import android.util.Log
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UserAccount(
    val name: String,
    val passwordV2: String?,
)

class DvrIpClient(private val host: String, private val port: Int = 34567) {
    private companion object {
        const val TAG = "DvrIpClient"
        const val SOFIA_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        const val MSG_LOGIN = 1000
        const val MSG_INFO_GET = 1020
        const val MSG_CONFIG_GET = 1042
        const val MSG_GET_RANDOM_USER = 1660
    }

    /**
     * Retrieves the real provisioned account (username + plaintext password) by
     * replaying the factory app's own recovery path: query the device's serial
     * number, then GetRandomUser, then decrypt its "Info"/"InfoUser" field with
     * the serial-derived AES key. See [[project-icsee-ble-pairing]] and
     * XiongmaiCrypto for how this was reverse-engineered from libFunSDK.so.
     */
    suspend fun queryRandomUserCredentials(adminUsername: String, adminPassword: String): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val socket = Socket(host, port)
                socket.soTimeout = 8000

                val sessionId = login(socket, adminUsername, adminPassword)
                if (sessionId == null) {
                    socket.close()
                    return@withContext null
                }

                val serialNumber = querySerialNumber(socket, sessionId)
                if (serialNumber == null) {
                    Log.e(TAG, "Failed to retrieve serial number")
                    socket.close()
                    return@withContext null
                }

                val infoBase64 = queryRandomUserInfo(socket, sessionId)
                socket.close()

                if (infoBase64 == null) {
                    Log.e(TAG, "Failed to retrieve GetRandomUser Info field")
                    return@withContext null
                }

                XiongmaiCrypto.decryptRandomUserInfo(infoBase64, serialNumber)
            } catch (e: Exception) {
                Log.e(TAG, "Exception retrieving random user credentials: ${e.message}", e)
                null
            }
        }

    suspend fun queryUserMap(username: String, password: String): List<UserAccount>? = withContext(Dispatchers.IO) {
        try {
            val socket = Socket(host, port)
            socket.soTimeout = 8000

            val sessionIdStr = login(socket, username, password)
            if (sessionIdStr == null) {
                socket.close()
                return@withContext null
            }
            val sid = sessionIdStr.removePrefix("0x").toLong(16).toInt()

            val queryJson = JSONObject().apply {
                put("Name", "System.ExUserMap")
                put("SessionID", sessionIdStr)
            }
            socket.getOutputStream().write(encodeFrame(sid, 1, MSG_CONFIG_GET, queryJson.toString().toByteArray() + byteArrayOf(0x0a, 0x00)))
            socket.getOutputStream().flush()

            val userMapResp = decodeFrame(socket)
            socket.close()
            if (userMapResp == null) return@withContext null

            val userMapJson = JSONObject(userMapResp.payload)
            val users = mutableListOf<UserAccount>()
            userMapJson.optJSONObject("System.ExUserMap")?.optJSONArray("User")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val user = arr.getJSONObject(i)
                    users.add(UserAccount(name = user.optString("Name", ""), passwordV2 = user.optString("PasswordV2", null)))
                }
            }
            users.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Exception querying user map: ${e.message}", e)
            null
        }
    }

    /** Logs in and returns the SessionID string (e.g. "0x00000010"), or null on failure. */
    private fun login(socket: Socket, username: String, password: String): String? {
        val loginRequestJson = JSONObject().apply {
            put("EncryptType", "MD5")
            put("LoginType", "DVRIP-Web")
            put("UserName", username)
            put("PassWord", sofiaHash(password))
        }
        socket.getOutputStream().write(encodeFrame(0, 0, MSG_LOGIN, loginRequestJson.toString().toByteArray() + byteArrayOf(0x0a, 0x00)))
        socket.getOutputStream().flush()

        val loginResp = decodeFrame(socket) ?: run {
            Log.e(TAG, "Failed to receive login response")
            return null
        }
        val loginResponseJson = JSONObject(loginResp.payload)
        if (loginResponseJson.optInt("Ret", -1) != 100) {
            Log.e(TAG, "Login failed: Ret=${loginResponseJson.optInt("Ret", -1)}")
            return null
        }
        return loginResponseJson.optString("SessionID", "")
    }

    private fun querySerialNumber(socket: Socket, sessionId: String): String? {
        val sid = sessionId.removePrefix("0x").toLong(16).toInt()
        val queryJson = JSONObject().apply {
            put("Name", "SystemInfo")
            put("SessionID", sessionId)
        }
        socket.getOutputStream().write(encodeFrame(sid, 1, MSG_INFO_GET, queryJson.toString().toByteArray() + byteArrayOf(0x0a, 0x00)))
        socket.getOutputStream().flush()

        val resp = decodeFrame(socket) ?: return null
        val json = JSONObject(resp.payload)
        return json.optJSONObject("SystemInfo")?.optString("SerialNo")
    }

    private fun queryRandomUserInfo(socket: Socket, sessionId: String): String? {
        val sid = sessionId.removePrefix("0x").toLong(16).toInt()
        val queryJson = JSONObject().apply {
            put("Name", "GetRandomUser")
            put("SessionID", sessionId)
        }
        socket.getOutputStream().write(encodeFrame(sid, 2, MSG_GET_RANDOM_USER, queryJson.toString().toByteArray() + byteArrayOf(0x0a, 0x00)))
        socket.getOutputStream().flush()

        val resp = decodeFrame(socket) ?: return null
        val json = JSONObject(resp.payload)
        val randomUser = json.optJSONObject("GetRandomUser") ?: return null
        return randomUser.optString("Info").ifBlank { null } ?: randomUser.optString("InfoUser").ifBlank { null }
    }

    private fun sofiaHash(password: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(password.toByteArray())
        return (0 until 8).map { i ->
            SOFIA_CHARS[((digest[i * 2].toInt() and 0xFF) + (digest[i * 2 + 1].toInt() and 0xFF)) % 62]
        }.joinToString("")
    }

    private fun encodeFrame(sessionId: Int, seq: Int, msgId: Int, payload: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(20).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put(0xFF.toByte())
            put(0x01.toByte())
            putShort(0)
            putInt(sessionId)
            putInt(seq)
            putShort(0)
            putShort(msgId.toShort())
            putInt(payload.size)
        }
        return header.array() + payload
    }

    private data class DvrFrame(val msgId: Int, val payload: String)

    private fun decodeFrame(socket: Socket): DvrFrame? {
        val header = ByteArray(20)
        if (socket.getInputStream().read(header) != 20) return null

        val buffer = ByteBuffer.wrap(header).apply { order(ByteOrder.LITTLE_ENDIAN) }
        buffer.position(14) // Skip to msgId (offset 14-15)
        val msgId = buffer.short.toInt() and 0xFFFF
        val payloadLen = buffer.int

        val payload = ByteArray(payloadLen)
        val read = socket.getInputStream().read(payload)
        if (read != payloadLen) return null

        return DvrFrame(msgId, String(payload.dropLastWhile { it == 0.toByte() }.toByteArray()))
    }
}
