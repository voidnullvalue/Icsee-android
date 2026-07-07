package com.voidnullvalue.icseelocal.config

import android.util.Base64
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
        const val SOFIA_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        const val MSG_LOGIN = 1000
        const val MSG_CONFIG_GET = 1042
    }

    suspend fun queryUserMap(username: String, password: String): List<UserAccount>? = withContext(Dispatchers.IO) {
        try {
            Log.d("DvrIpClient", "Querying user map from $host:$port as $username")
            val socket = Socket(host, port)
            socket.soTimeout = 8000

            // Login
            val sofiaPassword = sofiaHash(password)
            val loginRequestJson = JSONObject().apply {
                put("EncryptType", "MD5")
                put("LoginType", "DVRIP-Web")
                put("UserName", username)
                put("PassWord", sofiaPassword)
            }

            socket.getOutputStream().write(encodeFrame(0, 0, MSG_LOGIN, loginRequestJson.toString().toByteArray() + byteArrayOf(0x0a, 0x00)))
            socket.getOutputStream().flush()

            // Receive login response
            val loginResp = decodeFrame(socket)
            if (loginResp == null) {
                Log.e("DvrIpClient", "Failed to receive login response")
                socket.close()
                return@withContext null
            }

            val loginResponseJson = JSONObject(loginResp.payload)
            if (loginResponseJson.optInt("Ret", -1) != 100) {
                Log.e("DvrIpClient", "Login failed: ${loginResponseJson.optInt("Ret", -1)}")
                socket.close()
                return@withContext null
            }
            Log.d("DvrIpClient", "Login successful")

            val sessionId = loginResponseJson.optString("SessionID", "")
            val sid = sessionId.removePrefix("0x").toLong(16).toInt()

            // Query ExUserMap
            val queryJson = JSONObject().apply {
                put("Name", "System.ExUserMap")
                put("SessionID", sessionId)
            }

            socket.getOutputStream().write(encodeFrame(sid, 1, MSG_CONFIG_GET, queryJson.toString().toByteArray() + byteArrayOf(0x0a, 0x00)))
            socket.getOutputStream().flush()

            // Receive user map response
            val userMapResp = decodeFrame(socket)
            socket.close()

            if (userMapResp == null) {
                Log.e("DvrIpClient", "Failed to receive user map response")
                return@withContext null
            }

            val userMapJson = JSONObject(userMapResp.payload)
            val users = mutableListOf<UserAccount>()

            userMapJson.optJSONObject("System.ExUserMap")?.optJSONArray("User")?.let { arr ->
                Log.d("DvrIpClient", "Found ${arr.length()} users")
                for (i in 0 until arr.length()) {
                    val user = arr.getJSONObject(i)
                    users.add(UserAccount(
                        name = user.optString("Name", ""),
                        passwordV2 = user.optString("PasswordV2", null),
                    ))
                }
            }

            Log.d("DvrIpClient", "Returning ${users.size} user accounts")
            users.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e("DvrIpClient", "Exception querying user map: ${e.message}", e)
            null
        }
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
        buffer.position(12) // Skip to msgId
        val msgId = buffer.short.toInt() and 0xFFFF
        val payloadLen = buffer.int

        val payload = ByteArray(payloadLen)
        val read = socket.getInputStream().read(payload)
        if (read != payloadLen) return null

        return DvrFrame(msgId, String(payload.dropLastWhile { it == 0.toByte() }.toByteArray()))
    }
}
