package com.zhiliaohub.app.ui

import androidx.biometric.BiometricPrompt

internal enum class BiometricPromptPurpose {
    LOGIN_SIGNATURE,
    TOTP,
}

internal enum class BiometricPromptTerminalState {
    SUCCEEDED,
    FAILED,
    CANCELED,
    INTERRUPTED,
}

internal data class BiometricPromptLease(
    val purpose: BiometricPromptPurpose,
    val generation: Long,
)

internal sealed interface BiometricPromptStartDecision {
    data class Started(val lease: BiometricPromptLease) : BiometricPromptStartDecision

    data class Busy(val activeLease: BiometricPromptLease) : BiometricPromptStartDecision
}

/**
 * Serializes prompts without merging their purposes: login keeps its CryptoObject-backed
 * signature prompt, while TOTP display and removal keep their non-cryptographic prompt.
 * Android's onAuthenticationFailed is a non-terminal scan attempt, so callers must release
 * only from success or onAuthenticationError.
 */
internal class BiometricPromptCoordinator {
    private var nextGeneration = 1L

    var activeLease: BiometricPromptLease? = null
        private set

    var lastTerminalState: BiometricPromptTerminalState? = null
        private set

    fun tryStart(purpose: BiometricPromptPurpose): BiometricPromptStartDecision {
        val active = activeLease
        if (active != null) return BiometricPromptStartDecision.Busy(active)
        val lease = BiometricPromptLease(purpose, nextGeneration++)
        activeLease = lease
        return BiometricPromptStartDecision.Started(lease)
    }

    fun finish(
        lease: BiometricPromptLease,
        terminalState: BiometricPromptTerminalState,
    ): Boolean {
        if (activeLease != lease) return false
        activeLease = null
        lastTerminalState = terminalState
        return true
    }

    fun isActive(lease: BiometricPromptLease): Boolean = activeLease == lease

    fun abandonForLifecycle(): BiometricPromptLease? {
        val abandonedLease = activeLease ?: return null
        activeLease = null
        lastTerminalState = BiometricPromptTerminalState.INTERRUPTED
        return abandonedLease
    }

    val activePurpose: BiometricPromptPurpose?
        get() = activeLease?.purpose

    val canStartPrompt: Boolean
        get() = activeLease == null
}

internal fun biometricPromptBusyMessage(
    requestedPurpose: BiometricPromptPurpose,
    activePurpose: BiometricPromptPurpose,
): String = when {
    requestedPurpose == BiometricPromptPurpose.TOTP &&
        activePurpose == BiometricPromptPurpose.LOGIN_SIGNATURE ->
        "登录身份验证正在进行，请先完成当前的身份验证。"

    requestedPurpose == BiometricPromptPurpose.LOGIN_SIGNATURE &&
        activePurpose == BiometricPromptPurpose.TOTP ->
        "TOTP 身份验证正在进行，请先完成当前的身份验证，再重试登录。"

    else -> "身份验证正在进行，请先完成当前的身份验证。"
}

internal fun biometricPromptTerminalMessage(
    terminalState: BiometricPromptTerminalState,
    systemDetail: CharSequence,
): String = when (terminalState) {
    BiometricPromptTerminalState.CANCELED -> "已取消身份验证。"
    BiometricPromptTerminalState.INTERRUPTED ->
        "身份验证被系统或其他验证流程打断，请重试；本机密钥未被判定失效。"

    BiometricPromptTerminalState.FAILED -> "生物识别未完成：$systemDetail"
    BiometricPromptTerminalState.SUCCEEDED -> error("成功状态不需要错误提示。")
}

internal fun biometricTerminalState(errorCode: Int): BiometricPromptTerminalState = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    -> BiometricPromptTerminalState.CANCELED

    BiometricPrompt.ERROR_CANCELED -> BiometricPromptTerminalState.INTERRUPTED
    else -> BiometricPromptTerminalState.FAILED
}
