package com.voidnullvalue.icseelocal.ui.devicemanagement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAccountParserTest {

    // Shape of a real XM GetAllUser (msg 1473) response: the blank-password `admin`
    // backdoor alongside a real random-named admin account with a PasswordV2 set.
    private val realResponse = """
        {
          "Name": "GetAllUser",
          "Ret": 100,
          "SessionID": "0x0000001d",
          "Users": [
            { "AuthorityList": ["ShutDown"], "Group": "admin", "Memo": "admin 's account",
              "Name": "xkfu", "Password": "", "PasswordV2": "Cb0HPmw2VhI/k09s", "Reserved": true, "Sharable": true },
            { "AuthorityList": ["ShutDown"], "Group": "admin", "Memo": "factory test account",
              "Name": "admin", "Password": "", "PasswordV2": "", "Reserved": true, "Sharable": true },
            { "AuthorityList": [], "Group": "user", "Memo": "", "Name": "default",
              "Password": "", "PasswordV2": "", "Reserved": false, "Sharable": false }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses each account with its fields`() {
        val accounts = parseDeviceAccounts(realResponse)
        assertEquals(3, accounts.size)
        assertEquals(listOf("xkfu", "admin", "default"), accounts.map { it.name })
    }

    @Test
    fun `distinguishes the factory-test backdoor from the real per-device account`() {
        val accounts = parseDeviceAccounts(realResponse)
        val admin = accounts.first { it.name == "admin" }
        val real = accounts.first { it.name == "xkfu" }

        assertTrue("admin backdoor is flagged factory test", admin.memo.contains("factory test"))
        assertFalse("admin backdoor has no real password", admin.hasPassword || admin.hasPasswordV2)

        assertEquals("admin 's account", real.memo)
        assertTrue("the real account carries a PasswordV2", real.hasPasswordV2)
    }

    @Test
    fun `tolerates malformed or empty input`() {
        assertTrue(parseDeviceAccounts("not json").isEmpty())
        assertTrue(parseDeviceAccounts("""{"Ret":100}""").isEmpty())
        assertTrue(parseDeviceAccounts("""{"Users":[]}""").isEmpty())
    }
}
