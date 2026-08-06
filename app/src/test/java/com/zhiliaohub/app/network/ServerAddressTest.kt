package com.zhiliaohub.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAddressTest {
    @Test
    fun `normalizes a development HTTP origin`() {
        val address = ServerAddress.parse("  http://192.168.1.20:3001/  ")

        assertEquals("http://192.168.1.20:3001", address.normalized)
        assertTrue(address.isCleartext)
    }

    @Test
    fun `accepts HTTPS origin and normalizes trailing slash`() {
        val address = ServerAddress.parse("https://admin.example.com/")

        assertEquals("https://admin.example.com", address.normalized)
        assertFalse(address.isCleartext)
    }

    @Test
    fun `rejects credentials paths queries and unsupported schemes`() {
        listOf(
            "ftp://example.com",
            "https://user:secret@example.com",
            "https://example.com/admin",
            "https://example.com?debug=true",
            "",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                ServerAddress.parse(invalid)
            }
        }
    }

    @Test
    fun `origin comparison includes scheme host and effective port`() {
        val allowed = ServerAddress.parse("https://example.com")
        val same = ServerAddress.parse("https://example.com:443")
        val differentPort = ServerAddress.parse("https://example.com:8443")
        val differentScheme = ServerAddress.parse("http://example.com")

        assertTrue(ServerAddress.sameOrigin(allowed.httpUrl, same.httpUrl))
        assertFalse(ServerAddress.sameOrigin(allowed.httpUrl, differentPort.httpUrl))
        assertFalse(ServerAddress.sameOrigin(allowed.httpUrl, differentScheme.httpUrl))
    }
}

