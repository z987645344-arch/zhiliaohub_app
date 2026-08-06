package com.zhiliaohub.app.network

import java.util.Locale

object PairingCode {
    private val validPattern = Regex("^[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{5}-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{5}$")

    fun format(raw: String): String {
        val characters = raw.uppercase(Locale.ROOT)
            .filter { it in "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" }
            .take(10)
        return if (characters.length <= 5) characters
        else characters.take(5) + "-" + characters.drop(5)
    }

    fun isValid(value: String): Boolean = validPattern.matches(value.uppercase(Locale.ROOT))
}

