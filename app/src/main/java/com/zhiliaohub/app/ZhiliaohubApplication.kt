package com.zhiliaohub.app

import android.app.Application
import com.zhiliaohub.app.data.AppPreferences
import com.zhiliaohub.app.data.RegistrationManager
import com.zhiliaohub.app.network.ApiClientFactory
import com.zhiliaohub.app.security.DeviceKeyManager
import com.zhiliaohub.app.security.EncryptedSessionCookieJar

class ZhiliaohubApplication : Application() {
    lateinit var appPreferences: AppPreferences
        private set
    lateinit var deviceKeyManager: DeviceKeyManager
        private set
    lateinit var sessionCookieJar: EncryptedSessionCookieJar
        private set
    lateinit var registrationManager: RegistrationManager
        private set
    lateinit var apiClientFactory: ApiClientFactory
        private set

    override fun onCreate() {
        super.onCreate()
        appPreferences = AppPreferences(this)
        deviceKeyManager = DeviceKeyManager()
        sessionCookieJar = EncryptedSessionCookieJar(this)
        registrationManager = RegistrationManager(
            appPreferences = appPreferences,
            deviceKeyManager = deviceKeyManager,
            sessionCookieJar = sessionCookieJar,
        )
        apiClientFactory = ApiClientFactory(sessionCookieJar)
    }
}

