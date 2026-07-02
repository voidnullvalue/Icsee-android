package com.voidnullvalue.icseelocal.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DvripRsaPublicKeyTest {

    @Test
    fun `parses a real captured modulus and exponent`() {
        val hex = "AA183A9EDFD967854695DAFA321D440A70DD48A00E32245B54F8E71A61F41B8894323BDCF1245E4E9F1838C68E6A9C814C1E970F6DBF0BF4C780E06B3BAE3EF179262EF8843EB7EFC13E048A6C2C3635694918E64B7ACBC6FAC9771003C8467999A59BA9E0B1E95E75DC92376E26171B40807DC28D0E71D5F278AF76630BF025,010001"
        val key = DvripRsaPublicKey.parse(hex)
        assertEquals(1024, key.modulus.bitLength())
        assertEquals(65537L, key.publicExponent.toLong())
    }

    @Test
    fun `rejects a field without exactly one comma`() {
        assertThrows(IllegalArgumentException::class.java) { DvripRsaPublicKey.parse("nocomma") }
        assertThrows(IllegalArgumentException::class.java) { DvripRsaPublicKey.parse("a,b,c") }
    }
}
