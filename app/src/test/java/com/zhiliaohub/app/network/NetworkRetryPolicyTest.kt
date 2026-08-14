package com.zhiliaohub.app.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import javax.net.ssl.SSLHandshakeException

class NetworkRetryPolicyTest {
    @Test
    fun readOnlyRequestRetriesTwiceWithExpectedBackoff() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val policy = NetworkRetryPolicy(delayAction = { delays += it })

        val result = policy.execute<Unit>(RequestSafety.READ_ONLY) {
            attempts += 1
            ApiResult.NetworkFailure(SocketException("Connection reset"))
        }

        assertTrue(result is ApiResult.NetworkFailure)
        assertEquals(2, (result as ApiResult.NetworkFailure).automaticRetryCount)
        assertEquals(3, attempts)
        assertEquals(listOf(500L, 1_500L), delays)
    }

    @Test
    fun readOnlyRequestCanRecoverOnThirdAttempt() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val policy = NetworkRetryPolicy(delayAction = { delays += it })

        val result = policy.execute(RequestSafety.READ_ONLY) {
            attempts += 1
            if (attempts < 3) {
                ApiResult.NetworkFailure(SocketException("Connection reset"))
            } else {
                ApiResult.Success("online")
            }
        }

        assertTrue(result is ApiResult.Success)
        assertEquals("online", (result as ApiResult.Success).value)
        assertEquals(3, attempts)
        assertEquals(listOf(500L, 1_500L), delays)
    }

    @Test
    fun authenticationWritesNeverRetry() = runBlocking {
        var attempts = 0
        val delays = mutableListOf<Long>()
        val policy = NetworkRetryPolicy(delayAction = { delays += it })

        val result = policy.execute<Unit>(RequestSafety.AUTHENTICATION_WRITE) {
            attempts += 1
            ApiResult.NetworkFailure(SocketException("Connection reset"))
        }

        assertTrue(result is ApiResult.NetworkFailure)
        assertEquals(0, (result as ApiResult.NetworkFailure).automaticRetryCount)
        assertEquals(1, attempts)
        assertEquals(emptyList<Long>(), delays)
    }

    @Test
    fun httpAndTlsFailuresAreNotRetried() = runBlocking {
        val delays = mutableListOf<Long>()
        val policy = NetworkRetryPolicy(delayAction = { delays += it })
        val httpFailure = ApiResult.HttpFailure(429, "请求过于频繁")
        var httpAttempts = 0

        val httpResult = policy.execute<Unit>(RequestSafety.READ_ONLY) {
            httpAttempts += 1
            httpFailure
        }
        assertSame(httpFailure, httpResult)
        assertEquals(1, httpAttempts)

        var tlsAttempts = 0
        val tlsResult = policy.execute<Unit>(RequestSafety.READ_ONLY) {
            tlsAttempts += 1
            ApiResult.NetworkFailure(SSLHandshakeException("certificate rejected"))
        }
        assertTrue(tlsResult is ApiResult.NetworkFailure)
        assertEquals(0, (tlsResult as ApiResult.NetworkFailure).automaticRetryCount)
        assertEquals(1, tlsAttempts)
        assertEquals(emptyList<Long>(), delays)
    }

    @Test
    fun apiOperationsClassifyOnlySessionAndHealthAsReadOnly() {
        assertEquals(RequestSafety.READ_ONLY, ApiOperation.CHECK_SESSION.requestSafety)
        assertEquals(RequestSafety.READ_ONLY, ApiOperation.HEALTH.requestSafety)
        assertEquals(RequestSafety.AUTHENTICATION_WRITE, ApiOperation.PAIR_DEVICE.requestSafety)
        assertEquals(RequestSafety.AUTHENTICATION_WRITE, ApiOperation.REQUEST_CHALLENGE.requestSafety)
        assertEquals(RequestSafety.AUTHENTICATION_WRITE, ApiOperation.LOGIN.requestSafety)
    }
}
