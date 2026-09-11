package com.zhiliaohub.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpGeneratorTest {
    @Test
    fun rfc6238AppendixBSha1VectorsAllMatch() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ".toByteArray()
        val vectors = listOf(
            59L to "94287082",
            1_111_111_109L to "07081804",
            1_111_111_111L to "14050471",
            1_234_567_890L to "89005924",
            2_000_000_000L to "69279037",
            20_000_000_000L to "65353130",
        )

        vectors.forEach { (time, expected) ->
            assertEquals(expected, TotpGenerator.generate(secret, time, digits = 8))
        }
        secret.fill(0)
    }

    @Test
    fun rfc4648Base32AcceptsPaddedAndUnpaddedInputs() {
        assertArrayEquals("f".toByteArray(), Base32Codec.decode("MY======".toByteArray()))
        assertArrayEquals("f".toByteArray(), Base32Codec.decode("MY".toByteArray()))
        assertArrayEquals("foo".toByteArray(), Base32Codec.decode("MZXW6===".toByteArray()))
        assertArrayEquals("foo".toByteArray(), Base32Codec.decode("MZXW6".toByteArray()))
        assertArrayEquals("foobar".toByteArray(), Base32Codec.decode("MZXW6YTBOI======".toByteArray()))
        assertArrayEquals("foobar".toByteArray(), Base32Codec.decode("MZXW6YTBOI".toByteArray()))
    }

    @Test
    fun bindingInputIsCaseInsensitiveWhitespaceFreeAndOverwritten() {
        val input = "jbsw y3dp\tehpk 3pxp".toCharArray()

        val normalized = Base32Codec.normalizeAndValidate(input)

        assertArrayEquals("JBSWY3DPEHPK3PXP".toByteArray(), normalized)
        assertTrue(input.all { it == '\u0000' })
        normalized.fill(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidBase32IsRejected() {
        Base32Codec.normalizeAndValidate("NOT-BASE32".toCharArray())
    }

    @Test
    fun matchesSpeakeasySha1Base32ResultAtFixedWindow() {
        // speakeasy.totp({ secret: RFC_SECRET_BASE32, encoding: 'base32', time: 59 })
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ".toByteArray()
        assertEquals("287082", TotpGenerator.generate(secret, 59L))
        secret.fill(0)
    }
}
