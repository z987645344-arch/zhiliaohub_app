package com.zhiliaohub.app.ui

import com.zhiliaohub.app.network.ApiResult
import com.zhiliaohub.app.network.ServerAddress
import com.zhiliaohub.app.network.ZhiliaohubApi
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TotpUiStateTest {
    @Test
    fun codeIsInvisibleUntilBiometricGateUnlocksAndHiddenAgainAfterLock() {
        val gate = TotpDisplayGate()

        assertNull(gate.visibleCode("123456"))
        gate.unlock()
        assertEquals("123456", gate.visibleCode("123456"))
        gate.lock()
        assertNull(gate.visibleCode("123456"))
    }

    @Test
    fun refreshSessionFake401RequiresExistingReauthenticationFlow() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("test")
                    .body("{\"error\":\"session_expired\"}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val api = ZhiliaohubApi(
            ServerAddress.parse("https://example.com"),
            client,
        )

        val result = api.checkSession()

        assertEquals(SessionRefreshDecision.REAUTHENTICATE, sessionRefreshDecision(result))
        assertEquals(401, (result as ApiResult.HttpFailure).statusCode)
    }
}
