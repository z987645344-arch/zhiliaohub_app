package com.zhiliaohub.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingCodeTest {
    @Test
    fun `formats lowercase and pasted separators`() {
        assertEquals("ABCDE-23456", PairingCode.format("ab-cde 23456"))
    }

    @Test
    fun `limits input to ten unambiguous characters`() {
        assertEquals("ABCDE-23456", PairingCode.format("ABCDE23456XYZ"))
        assertEquals("ABCLD", PairingCode.format("A0B1CILOD"))
    }

    @Test
    fun `validates exact displayed format`() {
        assertTrue(PairingCode.isValid("ABCDE-23456"))
        assertFalse(PairingCode.isValid("ABCDE23456"))
        assertFalse(PairingCode.isValid("ABCDI-23456"))
        assertFalse(PairingCode.isValid("ABCDE-2345"))
    }
}
