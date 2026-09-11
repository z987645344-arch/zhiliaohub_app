package com.zhiliaohub.app.security

internal object Base32Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val validRemainders = setOf(0, 2, 4, 5, 7)
    private val paddingByRemainder = mapOf(0 to 0, 2 to 6, 4 to 4, 5 to 3, 7 to 1)

    /**
     * Normalizes a user-entered secret without ever creating a plaintext String.
     * The caller owns the returned bytes and must overwrite them after use.
     * The input array is overwritten on both success and failure.
     */
    fun normalizeAndValidate(input: CharArray): ByteArray {
        var normalized: ByteArray? = null
        try {
            val output = ByteArray(input.count { !it.isWhitespace() })
            normalized = output
            var outputIndex = 0
            input.forEach { character ->
                if (character.isWhitespace()) return@forEach
                val upper = character.uppercaseChar()
                require(upper == '=' || ALPHABET.indexOf(upper) >= 0) {
                    "密钥不是有效的 Base32 内容。"
                }
                output[outputIndex++] = upper.code.toByte()
            }
            require(output.isNotEmpty()) { "请输入 Base32 密钥。" }

            val decoded = decode(output)
            try {
                require(decoded.isNotEmpty()) { "Base32 密钥不能为空。" }
            } finally {
                decoded.fill(0)
            }
            normalized = null
            return output
        } finally {
            input.fill('\u0000')
            normalized?.fill(0)
        }
    }

    fun decode(input: ByteArray): ByteArray {
        require(input.isNotEmpty()) { "Base32 密钥不能为空。" }
        val firstPadding = input.indexOfFirst { it.toInt().toChar() == '=' }
        val dataLength = if (firstPadding >= 0) firstPadding else input.size
        val paddingLength = input.size - dataLength

        if (paddingLength > 0) {
            require(input.size % 8 == 0) { "Base32 填充长度不正确。" }
            require((dataLength until input.size).all { input[it].toInt().toChar() == '=' }) {
                "Base32 填充只能出现在末尾。"
            }
            require(paddingByRemainder[dataLength % 8] == paddingLength) {
                "Base32 填充与内容长度不匹配。"
            }
        } else {
            require(dataLength % 8 in validRemainders) { "Base32 内容长度不正确。" }
        }

        val output = ByteArray(dataLength * 5 / 8)
        var accumulator = 0
        var bits = 0
        var outputIndex = 0
        for (index in 0 until dataLength) {
            val value = ALPHABET.indexOf(input[index].toInt().toChar())
            require(value >= 0) { "密钥不是有效的 Base32 内容。" }
            accumulator = (accumulator shl 5) or value
            bits += 5
            if (bits >= 8) {
                bits -= 8
                output[outputIndex++] = (accumulator shr bits).toByte()
                accumulator = if (bits == 0) 0 else accumulator and ((1 shl bits) - 1)
            }
        }
        require(accumulator == 0) { "Base32 末尾包含非零填充位。" }
        return output
    }
}
