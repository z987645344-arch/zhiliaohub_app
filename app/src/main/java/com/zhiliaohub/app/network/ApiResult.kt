package com.zhiliaohub.app.network

import java.io.IOException

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class HttpFailure(val statusCode: Int, val message: String) : ApiResult<Nothing>
    data class NetworkFailure(val exception: IOException) : ApiResult<Nothing>
    data class ProtocolFailure(val message: String, val exception: Exception? = null) : ApiResult<Nothing>
}

data class Challenge(
    val challengeId: String,
    val signedPayload: String,
    val expiresAt: String,
)

data class HealthStatus(
    val isOnline: Boolean,
    val serverStatus: String,
)

