package com.zhiliaohub.app.security

import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object TotpGenerator {
    const val PERIOD_SECONDS = 30L
    const val DISPLAY_DIGITS = 6

    fun generate(base32Secret: ByteArray, unixTimeSeconds: Long, digits: Int = DISPLAY_DIGITS): String {
        require(unixTimeSeconds >= 0) { "时间不能为负数。" }
        require(digits in 6..8) { "TOTP 位数必须介于 6 与 8 之间。" }
        val secret = Base32Codec.decode(base32Secret)
        val counter = ByteArray(Long.SIZE_BYTES)
        var movingFactor = unixTimeSeconds / PERIOD_SECONDS
        for (index in counter.indices.reversed()) {
            counter[index] = movingFactor.toByte()
            movingFactor = movingFactor ushr 8
        }

        var digest: ByteArray? = null
        try {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secret, "HmacSHA1"))
            digest = mac.doFinal(counter)
            val offset = digest.last().toInt() and 0x0f
            val binary = ((digest[offset].toInt() and 0x7f) shl 24) or
                ((digest[offset + 1].toInt() and 0xff) shl 16) or
                ((digest[offset + 2].toInt() and 0xff) shl 8) or
                (digest[offset + 3].toInt() and 0xff)
            val modulus = when (digits) {
                6 -> 1_000_000
                7 -> 10_000_000
                else -> 100_000_000
            }
            return String.format(Locale.US, "%0${digits}d", binary % modulus)
        } finally {
            digest?.fill(0)
            counter.fill(0)
            secret.fill(0)
        }
    }

    fun remainingSeconds(unixTimeSeconds: Long): Int =
        (PERIOD_SECONDS - (unixTimeSeconds % PERIOD_SECONDS)).toInt()
}
