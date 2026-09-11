package com.zhiliaohub.app.network

import java.io.IOException

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class HttpFailure(val statusCode: Int, val message: String) : ApiResult<Nothing>
    data class NetworkFailure(
        val exception: IOException,
        val automaticRetryCount: Int = 0,
    ) : ApiResult<Nothing>
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

enum class BackupState(val wireValue: String) {
    OK("ok"),
    STALE("stale"),
    DISABLED("disabled"),
    UNKNOWN("unknown"),
    UNREACHABLE("unreachable");

    companion object {
        fun fromWireValue(value: String): BackupState = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("服务器返回了未知的备份状态。")
    }
}

data class ProjectBackupStatus(
    val status: BackupState,
    val hint: String,
)

data class BackupStatus(
    val zhiliaohub: ProjectBackupStatus,
    val zhitian: ProjectBackupStatus,
)
