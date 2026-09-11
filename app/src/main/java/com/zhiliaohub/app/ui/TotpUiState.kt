package com.zhiliaohub.app.ui

import com.zhiliaohub.app.network.ApiResult

internal enum class TotpBiometricAction {
    SHOW_CODE,
    UNBIND,
}

internal class TotpDisplayGate {
    var isUnlocked: Boolean = false
        private set
    var isMasked: Boolean = false
        private set

    fun unlock() {
        isUnlocked = true
        isMasked = false
    }

    fun toggleMask(): Boolean {
        if (!isUnlocked) return false
        isMasked = !isMasked
        return true
    }

    fun lock() {
        isUnlocked = false
        isMasked = false
    }

    fun visibleCode(code: String): String? = code.takeIf { isUnlocked && !isMasked }
}

internal enum class SessionRefreshDecision {
    SESSION_VALID,
    REAUTHENTICATE,
    CHECK_FAILED,
}

internal fun sessionRefreshDecision(result: ApiResult<Unit>): SessionRefreshDecision = when (result) {
    is ApiResult.Success -> SessionRefreshDecision.SESSION_VALID
    is ApiResult.HttpFailure -> if (result.statusCode == 401) {
        SessionRefreshDecision.REAUTHENTICATE
    } else {
        SessionRefreshDecision.CHECK_FAILED
    }
    is ApiResult.NetworkFailure,
    is ApiResult.ProtocolFailure,
    -> SessionRefreshDecision.CHECK_FAILED
}
