package com.voidnullvalue.icseelocal.session

import com.voidnullvalue.icseelocal.avtalk.FunSdkSequence
import com.voidnullvalue.icseelocal.crypto.DvripRsaPublicKey
import com.voidnullvalue.icseelocal.crypto.FunSdkSessionCrypto
import com.voidnullvalue.icseelocal.crypto.SofiaHash
import com.voidnullvalue.icseelocal.dvrip.DvripMessageIds
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.dvrip.DvripTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher

/**
 * Modern FunSDK login used by AVTalk-capable iCSee builds.
 *
 * Reimplemented from libFunSDK.so: 1413 OPMonitor/CONNECT_ALL pre-login,
 * RSA/PKCS#1-v1.5 wrapping of username/password/CommunicateKey, bootstrap
 * AES wrapping of message 1000, then AES-128-CBC session crypto using the
 * client-generated 16-character CommunicateKey.
 */
class FunSdkEncryptedLoginNegotiator(
    private val responseTimeoutMillis: Long = 5000,
    private val random: SecureRandom = SecureRandom(),
    private val randomStringFactory: ((Int) -> String)? = null,
) : LoginNegotiator {

    override suspend fun negotiate(transport: DvripTransport, credentials: CameraCredentials): AuthenticatedSession =
        negotiate(transport, credentials, FunSdkSequence())

    suspend fun negotiate(
        transport: DvripTransport,
        credentials: CameraCredentials,
        sequence: FunSdkSequence,
    ): AuthenticatedSession = withTimeout(responseTimeoutMillis) {
            coroutineScope {
                val randomA = randomString(4)
                val negotiationResponse = async(start = CoroutineStart.UNDISPATCHED) {
                    transport.incomingFrames
                        .filter { it.header.messageId == DvripMessageIds.MONITOR_RESPONSE }
                        .first()
                }
                transport.send(
                    session = PRELOGIN_HEADER_SESSION,
                    messageId = DvripMessageIds.MONITOR_REQUEST,
                    payload = preLoginRequest(randomA).toByteArray(Charsets.UTF_8), // native sends no terminator
                    sequenceOverride = sequence.nextUInt(),
                    headerByte12 = FUNSDK_AES_HEADER_MARKER,
                )
                val negotiationJson = DvripPayloads.decodeJsonOrNull(negotiationResponse.await().payload)
                    ?: throw LoginNegotiationBlockedException("message 1414 was not JSON")
                val negotiated = NegotiatedCrypto.parse(negotiationJson)
                if (negotiated.ret != 100) {
                    throw LoginNegotiationBlockedException("encrypted pre-login rejected: Ret=${negotiated.ret}", negotiated.ret)
                }
                require(negotiated.encryptAlgo == "RSA_V1.5") {
                    "unsupported FunSDK login encryption ${negotiated.encryptAlgo}"
                }
                require(negotiated.communicateEncryptAlgo == "AES" && negotiated.communicateBits == 128) {
                    "unsupported FunSDK session cipher ${negotiated.communicateEncryptAlgo}/${negotiated.communicateBits}"
                }
                val publicKey = DvripRsaPublicKey.parse(negotiated.publicKey)
                val passwordHash = if (negotiated.md5Dh) {
                    val randomB = negotiated.randomB
                        ?: throw LoginNegotiationBlockedException("camera requested MD5_DH without DHParameter/RandomStrB")
                    SofiaHash.hash(randomA + credentials.password + randomB)
                } else {
                    SofiaHash.hash(credentials.password)
                }
                val communicateKey = randomString(COMMUNICATE_KEY_LENGTH)
                val loginJson = buildLoginJson(
                    userName = rsaV15Hex(publicKey, credentials.username),
                    password = rsaV15Hex(publicKey, passwordHash),
                    communicateKey = rsaV15Hex(publicKey, communicateKey),
                )

                val loginResponse = async(start = CoroutineStart.UNDISPATCHED) {
                    transport.incomingFrames
                        .filter { it.header.messageId == DvripMessageIds.LOGIN_RESPONSE }
                        .first()
                }
                val encryptedLogin = FunSdkSessionCrypto.bootstrapEncrypt(loginJson.toByteArray(Charsets.UTF_8))
                val wireLogin = Base64.getEncoder().encodeToString(encryptedLogin)
                    .toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
                transport.send(
                    session = 0u,
                    messageId = DvripMessageIds.LOGIN_REQUEST,
                    payload = wireLogin,
                    sequenceOverride = sequence.nextUInt(),
                    headerByte12 = FUNSDK_AES_HEADER_MARKER,
                )
                val responseJson = DvripPayloads.decodeJsonOrNull(loginResponse.await().payload)
                    ?: throw LoginNegotiationBlockedException("message 1001 was not plaintext JSON")
                val login = ParsedLogin.parse(responseJson)
                if (login.ret != 100) {
                    throw LoginNegotiationBlockedException("encrypted login rejected: Ret=${login.ret}", login.ret)
                }
                AuthenticatedSession(
                    sessionId = login.sessionId,
                    aliveIntervalSeconds = login.aliveInterval,
                    transport = transport,
                    crypto = FunSdkSessionCrypto(
                        communicateKey.toByteArray(Charsets.US_ASCII),
                        negotiated.notEncryptMessageIds,
                    ),
                    adminToken = login.adminToken,
                )
            }
        }

    internal fun preLoginRequest(randomA: String): String =
        "{\"Name\":\"OPMonitor\",\"OPMonitor\":{\"Action\":\"Claim\",\"Parameter\":{" +
            "\"Channel\":0,\"CombinMode\":\"CONNECT_ALL\",\"StreamType\":\"Main\",\"TransMode\":\"TCP\"}}," +
            "\"DHParameter\":{\"RandomStrA\":\"$randomA\"},\"SessionID\":\"0x1\"}"

    internal fun buildLoginJson(userName: String, password: String, communicateKey: String): String =
        "{\"EncryptType\":\"MD5\",\"LoginType\":\"DVRIP-Web\",\"UserName\":\"$userName\"," +
            "\"PassWord\":\"$password\",\"CommunicateKey\":\"$communicateKey\"}"

    private fun rsaV15Hex(publicKey: RSAPublicKey, plaintext: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, random)
        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it.toInt() and 0xff) }
    }

    private fun randomString(length: Int): String =
        randomStringFactory?.invoke(length) ?: buildString(length) {
            repeat(length) {
                when (random.nextInt(3)) {
                    0 -> append(('A'.code + random.nextInt(26)).toChar())
                    1 -> append(('a'.code + random.nextInt(26)).toChar())
                    else -> append(('0'.code + random.nextInt(10)).toChar())
                }
            }
        }

    private data class NegotiatedCrypto(
        val ret: Int,
        val encryptAlgo: String,
        val communicateEncryptAlgo: String,
        val communicateBits: Int,
        val publicKey: String,
        val notEncryptMessageIds: Set<Int>,
        val md5Dh: Boolean,
        val randomB: String?,
    ) {
        companion object {
            fun parse(json: String): NegotiatedCrypto = NegotiatedCrypto(
                ret = ProtocolJson.int(json, "Ret") ?: 0,
                encryptAlgo = ProtocolJson.string(json, "EncryptAlgo").orEmpty(),
                communicateEncryptAlgo = ProtocolJson.string(json, "CommunicateEncryptAlgo").orEmpty(),
                communicateBits = ProtocolJson.int(json, "CommunicateBits") ?: 0,
                publicKey = ProtocolJson.string(json, "PublicKey").orEmpty(),
                notEncryptMessageIds = ProtocolJson.intArray(json, "NotEncryptMsgID").toSet(),
                md5Dh = ProtocolJson.bool(json, "MD5_DH") ?: false,
                randomB = ProtocolJson.string(json, "RandomStrB"),
            )
        }
    }

    private data class ParsedLogin(
        val ret: Int,
        val sessionId: UInt,
        val aliveInterval: Int,
        val adminToken: String?,
    ) {
        companion object {
            fun parse(json: String): ParsedLogin {
                val sessionText = ProtocolJson.string(json, "SessionID")
                    ?: throw LoginNegotiationBlockedException("message 1001 has no SessionID")
                val sessionId = sessionText.removePrefix("0x").removePrefix("0X").toUIntOrNull(16)
                    ?: throw LoginNegotiationBlockedException("invalid SessionID in message 1001: $sessionText")
                return ParsedLogin(
                    ret = ProtocolJson.int(json, "Ret") ?: 0,
                    sessionId = sessionId,
                    aliveInterval = ProtocolJson.int(json, "AliveInterval") ?: 0,
                    adminToken = ProtocolJson.string(json, "AdminToken"),
                )
            }
        }
    }

    private object ProtocolJson {
        fun string(json: String, key: String): String? {
            val re = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
            return re.find(json)?.groupValues?.get(1)?.let(::unescape)
        }

        fun int(json: String, key: String): Int? {
            val re = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?\\d+)")
            return re.find(json)?.groupValues?.get(1)?.toIntOrNull()
        }

        fun bool(json: String, key: String): Boolean? {
            val re = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(true|false|1|0)", RegexOption.IGNORE_CASE)
            return when (re.find(json)?.groupValues?.get(1)?.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
        }

        fun intArray(json: String, key: String): List<Int> {
            val re = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\[([^]]*)]", setOf(RegexOption.DOT_MATCHES_ALL))
            val body = re.find(json)?.groupValues?.get(1) ?: return emptyList()
            return Regex("-?\\d+").findAll(body).mapNotNull { it.value.toIntOrNull() }.toList()
        }

        private fun unescape(value: String): String = buildString(value.length) {
            var i = 0
            while (i < value.length) {
                val c = value[i++]
                if (c != '\\' || i >= value.length) {
                    append(c)
                    continue
                }
                when (val e = value[i++]) {
                    '\\' -> append('\\')
                    '"' -> append('"')
                    '/' -> append('/')
                    'b' -> append('\b')
                    'f' -> append('\u000c')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'u' -> {
                        if (i + 4 <= value.length) {
                            append(value.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        } else {
                            append('u')
                        }
                    }
                    else -> append(e)
                }
            }
        }
    }

    companion object {
        const val PRELOGIN_HEADER_SESSION: UInt = 99_999u
        const val COMMUNICATE_KEY_LENGTH = 16
        /** InitMsg header byte 12 used by the stock AES pre-login/login path. */
        const val FUNSDK_AES_HEADER_MARKER = 99
    }
}
