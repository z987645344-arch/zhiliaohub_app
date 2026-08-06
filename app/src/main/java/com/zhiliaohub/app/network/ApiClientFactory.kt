package com.zhiliaohub.app.network

import com.zhiliaohub.app.security.EncryptedSessionCookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClientFactory(
    private val sessionCookieJar: EncryptedSessionCookieJar,
) {
    fun create(serverUrl: String): ZhiliaohubApi {
        val address = ServerAddress.parse(serverUrl)
        val client = OkHttpClient.Builder()
            .cookieJar(sessionCookieJar)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(OriginLockingInterceptor(address))
            .build()
        return ZhiliaohubApi(address, client)
    }
}

private class OriginLockingInterceptor(
    private val allowedAddress: ServerAddress,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val requestUrl = chain.request().url
        if (!ServerAddress.sameOrigin(allowedAddress.httpUrl, requestUrl)) {
            throw IOException("请求目标与用户配置的服务器地址不一致，已阻止请求。")
        }
        return chain.proceed(chain.request())
    }
}

