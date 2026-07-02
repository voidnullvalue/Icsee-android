package com.voidnullvalue.icseelocal.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreLoginNegotiationTest {

    // Real message-1011 response captured live from the target camera
    // (192.168.1.100:34567) on 2026-07-01 via tools/live/probe_1010.py.
    // See PROTOCOL_NOTES.md "Live verification". Not fabricated.
    private val realResponseJson = """
        { "Bits" : 1024, "CommunicateBits" : 128, "CommunicateEncryptAlgo" : "AES", "EncryptAlgo" : "RSA_V1.5", "NotEncryptMsgID" : [ 1000, 1001, 1008, 1009, 1010, 1011, 1050, 1054, 1412, 1413, 1414, 1422, 1424, 1425, 1426, 1432, 1433, 1434, 1435, 1449, 1522, 1572, 1576, 1580, 1582, 1645, 2062, 2063, 2123, 2140, 3016, 3502 ], "PublicKey" : "AA183A9EDFD967854695DAFA321D440A70DD48A00E32245B54F8E71A61F41B8894323BDCF1245E4E9F1838C68E6A9C814C1E970F6DBF0BF4C780E06B3BAE3EF179262EF8843EB7EFC13E048A6C2C3635694918E64B7ACBC6FAC9771003C8467999A59BA9E0B1E95E75DC92376E26171B40807DC28D0E71D5F278AF76630BF025,010001", "Ret" : 100 }
    """.trimIndent()

    @Test
    fun `parses the real camera pre-login negotiation response`() {
        val negotiation = PreLoginNegotiationParser.parse(realResponseJson)
        requireNotNull(negotiation)
        assertEquals(1024, negotiation.bits)
        assertEquals(128, negotiation.communicateBits)
        assertEquals("AES", negotiation.communicateEncryptAlgo)
        assertEquals("RSA_V1.5", negotiation.encryptAlgo)
        assertEquals(100, negotiation.ret)
        assertTrue(negotiation.success)
        assertTrue(1400 !in negotiation.notEncryptMessageIds) // PTZ control must go through the encrypted envelope
        assertTrue(1412 in negotiation.notEncryptMessageIds) // media stream confirmed unencrypted at transport layer
    }

    @Test
    fun `parsed RSA public key has the exact confirmed modulus size and exponent`() {
        val negotiation = PreLoginNegotiationParser.parse(realResponseJson)
        requireNotNull(negotiation)
        assertEquals(1024, negotiation.publicKey!!.modulus.bitLength())
        assertEquals(65537L, negotiation.publicKey!!.publicExponent.toLong())
    }

    @Test
    fun `two live captures returned different moduli, confirming key rotation`() {
        // Second real capture, same session, few seconds later.
        val secondCaptureJson = realResponseJson.replace(
            "AA183A9EDFD967854695DAFA321D440A70DD48A00E32245B54F8E71A61F41B8894323BDCF1245E4E9F1838C68E6A9C814C1E970F6DBF0BF4C780E06B3BAE3EF179262EF8843EB7EFC13E048A6C2C3635694918E64B7ACBC6FAC9771003C8467999A59BA9E0B1E95E75DC92376E26171B40807DC28D0E71D5F278AF76630BF025",
            "B2C591FD71ABB550AAA12CCCA12FBDD44DBD468398E823767288BCAB4244F6DACCDDCD87C260A445A1AC34694F237786A3F839D495B38326F20959A3023282146C6DD9461CADCF43369C3E16FEF429738A9F461382FA91C9E2807FD22985A5B930229443F48EF8F23786B2FD9801796DDFADB2198BCAC789686D55806ADDEF37",
        )
        val first = PreLoginNegotiationParser.parse(realResponseJson)
        val second = PreLoginNegotiationParser.parse(secondCaptureJson)
        requireNotNull(first)
        requireNotNull(second)
        assertTrue(first.publicKey!!.modulus != second.publicKey!!.modulus)
    }

    @Test
    fun `real captured RSA key round trips through standard javax RSA cipher`() {
        val negotiation = PreLoginNegotiationParser.parse(realResponseJson)
        requireNotNull(negotiation)
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, negotiation.publicKey!!)
        val plaintext = "hello-camera".toByteArray()
        val ciphertext = cipher.doFinal(plaintext)
        assertEquals(128, ciphertext.size) // 1024-bit modulus -> 128-byte RSA block
    }
}
