package com.zhiliaohub.app.network

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class RequestSafety {
    READ_ONLY,
    AUTHENTICATION_WRITE,
}

internal enum class ApiOperation(val requestSafety: RequestSafety) {
    CHECK_SESSION(RequestSafety.READ_ONLY),
    HEALTH(RequestSafety.READ_ONLY),
    BACKUP_STATUS(RequestSafety.READ_ONLY),
    PAIR_DEVICE(RequestSafety.AUTHENTICATION_WRITE),
    REQUEST_CHALLENGE(RequestSafety.AUTHENTICATION_WRITE),
    LOGIN(RequestSafety.AUTHENTICATION_WRITE),
}

internal class NetworkRetryPolicy(
    private val retryDelaysMillis: List<Long> = listOf(500L, 1_500L),
    private val delayAction: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun <T> execute(
        requestSafety: RequestSafety,
        operation: suspend () -> ApiResult<T>,
    ): ApiResult<T> {
        var result = operation()
        if (requestSafety != RequestSafety.READ_ONLY) return result
        var retriesPerformed = 0

        for (retryDelay in retryDelaysMillis) {
            val failure = result as? ApiResult.NetworkFailure
                ?: return result.withAutomaticRetryCount(retriesPerformed)
            if (!failure.exception.isRetryableTransportFailure()) {
                return result.withAutomaticRetryCount(retriesPerformed)
            }
            delayAction(retryDelay)
            retriesPerformed += 1
            result = operation()
        }
        return result.withAutomaticRetryCount(retriesPerformed)
    }
}

private fun <T> ApiResult<T>.withAutomaticRetryCount(retryCount: Int): ApiResult<T> =
    if (this is ApiResult.NetworkFailure && retryCount > 0) {
        copy(automaticRetryCount = retryCount)
    } else {
        this
    }

private fun IOException.isRetryableTransportFailure(): Boolean {
    val causes = generateSequence(this as Throwable?) { it.cause }.toList()
    if (causes.any { it is SSLException }) return false
    if (causes.any { it.message?.contains("已阻止请求") == true }) return false
    return causes.any {
        it is UnknownHostException ||
            it is SocketTimeoutException ||
            it is ConnectException ||
            it is SocketException
    } || this::class == IOException::class
}
