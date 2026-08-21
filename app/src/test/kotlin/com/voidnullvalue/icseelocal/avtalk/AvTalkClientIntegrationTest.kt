package com.voidnullvalue.icseelocal.avtalk

import com.voidnullvalue.icseelocal.crypto.FunSdkSessionCrypto
import com.voidnullvalue.icseelocal.crypto.SofiaHash
import com.voidnullvalue.icseelocal.dvrip.DvripFrame
import com.voidnullvalue.icseelocal.dvrip.DvripHeader
import com.voidnullvalue.icseelocal.dvrip.DvripPayloads
import com.voidnullvalue.icseelocal.session.CameraCredentials
import com.voidnullvalue.icseelocal.session.FunSdkEncryptedLoginNegotiator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.LocalDateTime
import java.util.Base64
import javax.crypto.Cipher

class AvTalkClientIntegrationTest {
    @Test
    fun `replays the recovered FunSDK AVTalk flow end to end`() = runBlocking {
        coroutineScope {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
            val publicKey = keyPair.public as RSAPublicKey
            val publicKeyField = publicKey.modulus.toString(16).uppercase() + "," +
                publicKey.publicExponent.toString(16).uppercase()
            val notEncrypted = setOf(
                1000, 1001, 1008, 1009, 1010, 1011, 1050, 1054, 1412, 1413, 1414,
                1422, 1424, 1425, 1426, 1432, 1433, 1434, 1435, 1449, 1522, 1572,
                1576, 1580, 1582, 1645, 2062, 2063, 2123, 2140, 3016, 3502,
            )
            val randomA = "Aa1B"
            val randomB = "z9Y2"
            val communicateKey = "Ab1Cd2Ef3Gh4Ij5K"
            val sessionId = 0x3cu
            val server = ServerSocket(0)

            val serverJob = async(Dispatchers.IO) {
                val control = server.accept().apply { soTimeout = 10_000 }

                val preLogin = readFrame(control)
                assertEquals(1413, preLogin.header.messageId)
                assertEquals(99_999u, preLogin.header.session)
                assertEquals(1016u, preLogin.header.sequence)
                assertEquals(0x63, preLogin.header.headerByte12)
                assertEquals(0, preLogin.header.headerByte13)
                assertEquals('}'.code, preLogin.payload.last().toInt() and 0xff) // no terminator
                val preLoginJson = preLogin.payload.toString(Charsets.UTF_8)
                assertTrue(preLoginJson.contains("\"TransMode\":\"TCP\""))
                assertTrue(preLoginJson.contains("\"RandomStrA\":\"$randomA\""))

                val negotiation = "{\"Bits\":1024,\"CommunicateBits\":128,\"CommunicateEncryptAlgo\":\"AES\"," +
                    "\"EncryptAlgo\":\"RSA_V1.5\",\"LoginEncryptionType\":{\"MD5_DH\":true,\"TOKEN_DH\":false}," +
                    "\"DHParameter\":{\"RandomStrB\":\"$randomB\"},\"NotEncryptMsgID\":[${notEncrypted.joinToString()}]," +
                    "\"PublicKey\":\"$publicKeyField\",\"Ret\":100}"
                writeFrame(control, 99_999u, 1016u, 1414, DvripPayloads.encodeJson(negotiation))

                val loginFrame = readFrame(control)
                assertEquals(1000, loginFrame.header.messageId)
                assertEquals(0u, loginFrame.header.session)
                assertEquals(1024u, loginFrame.header.sequence)
                assertEquals(0x63, loginFrame.header.headerByte12)
                val loginCiphertext = Base64.getDecoder().decode(loginFrame.payload.copyOf(loginFrame.payload.size - 1))
                val loginJson = FunSdkSessionCrypto.bootstrapDecrypt(loginCiphertext).toString(Charsets.UTF_8)
                assertEquals("user", rsaDecrypt(keyPair.private, jsonStringField(loginJson, "UserName")))
                assertEquals(
                    SofiaHash.hash(randomA + "pass" + randomB),
                    rsaDecrypt(keyPair.private, jsonStringField(loginJson, "PassWord")),
                )
                assertEquals(
                    communicateKey,
                    rsaDecrypt(keyPair.private, jsonStringField(loginJson, "CommunicateKey")),
                )
                writeFrame(
                    control,
                    0u,
                    1024u,
                    1001,
                    DvripPayloads.encodeJson(
                        "{\"AliveInterval\":3600,\"ChannelNum\":1,\"Ret\":100,\"SessionID\":\"0x0000003c\"}",
                    ),
                )

                val crypto = FunSdkSessionCrypto(communicateKey.toByteArray(Charsets.US_ASCII), notEncrypted)

                val ability = readFrame(control)
                assertEquals(1360, ability.header.messageId)
                assertEquals(1032u, ability.header.sequence)
                assertEquals(AvTalkPayloads.encodeCapability(sessionId), decryptCommand(crypto, ability))
                writeEncryptedResponse(control, crypto, sessionId, ability.header.sequence, 1361, "{\"Ret\":100}")

                val media = server.accept().apply { soTimeout = 10_000 }
                val claim = readFrame(media)
                assertEquals(AvTalkMessageIds.CLAIM_REQUEST, claim.header.messageId)
                assertEquals(sessionId, claim.header.session)
                assertEquals(1040u, claim.header.sequence)
                assertEquals(0, claim.header.headerByte12)
                assertEquals(0, claim.payload.last().toInt())
                assertEquals(
                    AvTalkPayloads.claim(sessionId),
                    claim.payload.copyOf(claim.payload.size - 1).toString(Charsets.UTF_8),
                )
                writeFrame(media, sessionId, claim.header.sequence, AvTalkMessageIds.CLAIM_RESPONSE, DvripPayloads.encodeJson("{\"Ret\":100}"))

                val start = readFrame(control)
                assertEquals(AvTalkMessageIds.CONTROL_REQUEST, start.header.messageId)
                assertEquals(1048u, start.header.sequence)
                assertEquals(0, start.header.headerByte12)
                assertEquals(AvTalkPayloads.start(sessionId), decryptCommand(crypto, start))
                writeEncryptedResponse(control, crypto, sessionId, start.header.sequence, AvTalkMessageIds.CONTROL_RESPONSE, "{\"Ret\":100}")

                val keyPart1 = readFrame(media)
                val keyPart2 = readFrame(media)
                assertEquals(AvTalkMessageIds.MEDIA_UPSTREAM, keyPart1.header.messageId)
                assertEquals(0u, keyPart1.header.sequence)
                assertEquals(0u, keyPart2.header.sequence)
                assertEquals(0x20, keyPart1.header.headerByte12)
                assertEquals(0x20, keyPart2.header.headerByte12)
                assertEquals(0, keyPart1.header.headerByte13)
                assertEquals(AvTalkClient.FUNSDK_MEDIA_CHUNK_SIZE, keyPart1.payload.size)
                val fullKeyFrame = keyPart1.payload + keyPart2.payload
                assertEquals(70_016, fullKeyFrame.size)
                assertEquals("000001fc", toHex(fullKeyFrame.copyOfRange(0, 4)))

                val inter = readFrame(media)
                assertEquals(0u, inter.header.sequence)
                assertEquals(0x28, inter.header.headerByte12)
                assertEquals("000001fd", toHex(inter.payload.copyOfRange(0, 4)))

                val audio = readFrame(media)
                assertEquals(0u, audio.header.sequence)
                assertEquals(0x30, audio.header.headerByte12)
                assertEquals("000001fa0e024001", toHex(audio.payload.copyOfRange(0, 8)))

                val stop = readFrame(control)
                assertEquals(AvTalkMessageIds.CONTROL_REQUEST, stop.header.messageId)
                assertEquals(1080u, stop.header.sequence)
                assertEquals(AvTalkPayloads.stop(sessionId), decryptCommand(crypto, stop))
                writeEncryptedResponse(control, crypto, sessionId, stop.header.sequence, AvTalkMessageIds.CONTROL_RESPONSE, "{\"Ret\":100}")
                media.close()

                val postStopAbility = readFrame(control)
                assertEquals(1360, postStopAbility.header.messageId)
                assertEquals(1088u, postStopAbility.header.sequence)
                assertEquals(AvTalkPayloads.encodeCapability(sessionId), decryptCommand(crypto, postStopAbility))
                writeEncryptedResponse(control, crypto, sessionId, postStopAbility.header.sequence, 1361, "{\"Ret\":100}")
                control.close()
            }

            val negotiator = FunSdkEncryptedLoginNegotiator(
                randomStringFactory = { length -> if (length == 4) randomA else communicateKey },
            )
            val client = AvTalkClient(
                host = "127.0.0.1",
                port = server.localPort,
                credentials = CameraCredentials("user", "pass"),
                mediaStartDelayMillis = 0,
                postStopCapabilityRefreshMillis = 0,
                loginNegotiator = negotiator,
            )

            client.start()
            assertTrue(client.isActive)
            val annexB = ByteArray(70_000) { 1 }.also {
                it[0] = 0
                it[1] = 0
                it[2] = 0
                it[3] = 1
            }
            client.sendH265KeyFrame(annexB, LocalDateTime.of(2026, 8, 21, 12, 34, 56))
            client.sendH265InterFrame(ByteArray(100) { 2 })
            client.sendAlawAudio(ByteArray(320) { 3 })
            client.stop()
            assertFalse(client.isActive)

            serverJob.await()
            server.close()
        }
    }

    private fun readFrame(socket: Socket): DvripFrame {
        val input = socket.getInputStream()
        val headerBytes = ByteArray(DvripHeader.HEADER_LEN)
        readFully(input, headerBytes)
        val header = DvripHeader.decode(headerBytes)
        val payload = ByteArray(header.payloadLength)
        readFully(input, payload)
        return DvripFrame(header, payload)
    }

    private fun readFully(input: java.io.InputStream, dst: ByteArray) {
        var offset = 0
        while (offset < dst.size) {
            val n = input.read(dst, offset, dst.size - offset)
            check(n > 0) { "socket closed while reading ${dst.size} bytes" }
            offset += n
        }
    }

    private fun writeFrame(socket: Socket, session: UInt, sequence: UInt, id: Int, payload: ByteArray) {
        socket.getOutputStream().apply {
            write(DvripFrame.of(session, sequence, id, payload).encode())
            flush()
        }
    }

    private fun writeEncryptedResponse(
        socket: Socket,
        crypto: FunSdkSessionCrypto,
        session: UInt,
        sequence: UInt,
        id: Int,
        json: String,
    ) {
        val ciphertext = crypto.encrypt(id, json.toByteArray(Charsets.UTF_8))
        val payload = Base64.getEncoder().encodeToString(ciphertext).toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        writeFrame(socket, session, sequence, id, payload)
    }

    private fun decryptCommand(crypto: FunSdkSessionCrypto, frame: DvripFrame): String {
        val ciphertext = Base64.getDecoder().decode(frame.payload.copyOf(frame.payload.size - 1))
        return crypto.decrypt(frame.header.messageId, ciphertext).toString(Charsets.UTF_8)
    }

    private fun rsaDecrypt(privateKey: PrivateKey, hex: String): String {
        val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(bytes).toString(Charsets.UTF_8)
    }

    private fun jsonStringField(json: String, key: String): String =
        Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
            .find(json)?.groupValues?.get(1)
            ?: error("missing $key in $json")

    private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
