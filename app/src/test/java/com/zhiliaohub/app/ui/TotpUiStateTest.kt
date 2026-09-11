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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TotpUiStateTest {
    @Test
    fun maskToggleHasNoEffectBeforeBiometricGateUnlocks() {
        val gate = TotpDisplayGate()

        assertFalse(gate.toggleMask())
        assertFalse(gate.isUnlocked)
        assertFalse(gate.isMasked)
        assertNull(gate.visibleCode("123456"))
    }

    @Test
    fun biometricUnlockStartsWithCodeVisible() {
        val gate = TotpDisplayGate()

        gate.unlock()

        assertTrue(gate.isUnlocked)
        assertFalse(gate.isMasked)
        assertEquals("123456", gate.visibleCode("123456"))
    }

    @Test
    fun maskToggleDoesNotLockGateAndUnmaskShowsCurrentWindowCode() {
        val gate = TotpDisplayGate()
        gate.unlock()

        assertTrue(gate.toggleMask())
        assertTrue(gate.isUnlocked)
        assertTrue(gate.isMasked)
        assertNull(gate.visibleCode("123456"))

        assertTrue(gate.toggleMask())
        assertTrue(gate.isUnlocked)
        assertFalse(gate.isMasked)
        assertEquals("654321", gate.visibleCode("654321"))
    }

    @Test
    fun lockResetsBothBiometricAndMaskAxes() {
        val gate = TotpDisplayGate()
        gate.unlock()
        gate.toggleMask()

        gate.lock()

        assertFalse(gate.isUnlocked)
        assertFalse(gate.isMasked)
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
