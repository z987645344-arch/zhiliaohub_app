package com.zhiliaohub.app.ui

import com.zhiliaohub.app.network.BackupState
import com.zhiliaohub.app.network.BackupStatus

internal enum class BackupStatusTone {
    LOW_KEY,
    NEUTRAL,
    DANGER,
}

internal data class BackupStatusPresentation(
    val label: String,
    val tone: BackupStatusTone,
)

internal fun BackupState.toPresentation(): BackupStatusPresentation = when (this) {
    BackupState.OK -> BackupStatusPresentation("正常", BackupStatusTone.LOW_KEY)
    BackupState.DISABLED -> BackupStatusPresentation("已停用", BackupStatusTone.NEUTRAL)
    BackupState.STALE -> BackupStatusPresentation("已超期", BackupStatusTone.DANGER)
    BackupState.UNKNOWN -> BackupStatusPresentation("未知", BackupStatusTone.DANGER)
    BackupState.UNREACHABLE -> BackupStatusPresentation("不可达", BackupStatusTone.DANGER)
}

internal data class BackupStatusCardState(
    val latest: BackupStatus? = null,
    val fetchFailure: String? = null,
) {
    fun loaded(value: BackupStatus): BackupStatusCardState = BackupStatusCardState(latest = value)

    fun unavailable(message: String): BackupStatusCardState = copy(fetchFailure = message)
}
