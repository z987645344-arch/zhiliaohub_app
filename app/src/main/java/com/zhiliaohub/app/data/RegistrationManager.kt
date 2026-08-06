package com.zhiliaohub.app.data

import com.zhiliaohub.app.security.DeviceKeyManager
import com.zhiliaohub.app.security.EncryptedSessionCookieJar

class RegistrationManager(
    private val appPreferences: AppPreferences,
    private val deviceKeyManager: DeviceKeyManager,
    private val sessionCookieJar: EncryptedSessionCookieJar,
) {
    suspend fun resetPairing() {
        sessionCookieJar.clear()
        deviceKeyManager.deleteSigningKey()
        appPreferences.setPaired(false)
    }

    fun clearSession() {
        sessionCookieJar.clear()
    }
}

