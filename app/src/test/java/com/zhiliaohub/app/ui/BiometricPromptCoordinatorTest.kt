package com.zhiliaohub.app.ui

import androidx.biometric.BiometricPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricPromptCoordinatorTest {
    @Test
    fun loginPromptInFlightRejectsTotpBeforeSecondPromptStarts() {
        val coordinator = BiometricPromptCoordinator()
        val loginLease = startedLease(coordinator.tryStart(BiometricPromptPurpose.LOGIN_SIGNATURE))

        val decision = coordinator.tryStart(BiometricPromptPurpose.TOTP)
        var secondPromptLaunchCount = 0
        if (decision is BiometricPromptStartDecision.Started) secondPromptLaunchCount += 1

        assertEquals(BiometricPromptStartDecision.Busy(loginLease), decision)
        assertEquals(0, secondPromptLaunchCount)
        assertEquals(loginLease, coordinator.activeLease)
        assertFalse(coordinator.canStartPrompt)
        assertEquals(
            "登录身份验证正在进行，请先完成当前的身份验证。",
            biometricPromptBusyMessage(
                BiometricPromptPurpose.TOTP,
                (decision as BiometricPromptStartDecision.Busy).activeLease.purpose,
            ),
        )
    }

    @Test
    fun totpPromptInFlightRejectsAutomaticLoginWithoutReplacingIt() {
        val coordinator = BiometricPromptCoordinator()
        val totpLease = startedLease(coordinator.tryStart(BiometricPromptPurpose.TOTP))

        val decision = coordinator.tryStart(BiometricPromptPurpose.LOGIN_SIGNATURE)
        var automaticLoginPromptLaunchCount = 0
        if (decision is BiometricPromptStartDecision.Started) automaticLoginPromptLaunchCount += 1

        assertEquals(BiometricPromptStartDecision.Busy(totpLease), decision)
        assertEquals(0, automaticLoginPromptLaunchCount)
        assertEquals(totpLease, coordinator.activeLease)
        assertEquals(
            "TOTP 身份验证正在进行，请先完成当前的身份验证，再重试登录。",
            biometricPromptBusyMessage(
                BiometricPromptPurpose.LOGIN_SIGNATURE,
                (decision as BiometricPromptStartDecision.Busy).activeLease.purpose,
            ),
        )
    }

    @Test
    fun lifecycleStopAbandonsActiveLeaseAndAllowsRetry() {
        BiometricPromptPurpose.entries.forEach { purpose ->
            val coordinator = BiometricPromptCoordinator()
            val lease = startedLease(coordinator.tryStart(purpose))

            assertEquals(lease, coordinator.abandonForLifecycle())
            assertNull(coordinator.activeLease)
            assertTrue(coordinator.canStartPrompt)
            assertEquals(BiometricPromptTerminalState.INTERRUPTED, coordinator.lastTerminalState)
            assertTrue(coordinator.tryStart(purpose) is BiometricPromptStartDecision.Started)
        }
    }

    @Test
    fun everyTerminalErrorCodeReleasesTheMatchingLease() {
        val terminalErrorCodes = listOf(
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
            BiometricPrompt.ERROR_TIMEOUT,
            BiometricPrompt.ERROR_NO_SPACE,
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_VENDOR,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
        )

        terminalErrorCodes.forEach { errorCode ->
            val coordinator = BiometricPromptCoordinator()
            val lease = startedLease(coordinator.tryStart(BiometricPromptPurpose.LOGIN_SIGNATURE))
            val terminalState = biometricTerminalState(errorCode)

            assertTrue(coordinator.finish(lease, terminalState))
            assertTrue(coordinator.canStartPrompt)
            assertEquals(terminalState, coordinator.lastTerminalState)
        }
    }

    @Test
    fun successFailureAndCancelAllReleaseForTheNextPurpose() {
        BiometricPromptTerminalState.entries.forEach { terminalState ->
            val coordinator = BiometricPromptCoordinator()
            val lease = startedLease(coordinator.tryStart(BiometricPromptPurpose.LOGIN_SIGNATURE))

            assertTrue(coordinator.finish(lease, terminalState))
            assertTrue(
                coordinator.tryStart(BiometricPromptPurpose.TOTP) is
                    BiometricPromptStartDecision.Started,
            )
        }
    }

    @Test
    fun lateCallbackCannotReleaseNewLeaseOfTheSamePurpose() {
        val coordinator = BiometricPromptCoordinator()
        val oldLease = startedLease(coordinator.tryStart(BiometricPromptPurpose.LOGIN_SIGNATURE))
        coordinator.abandonForLifecycle()
        val newLease = startedLease(coordinator.tryStart(BiometricPromptPurpose.LOGIN_SIGNATURE))

        assertFalse(coordinator.finish(oldLease, BiometricPromptTerminalState.CANCELED))
        assertEquals(newLease, coordinator.activeLease)
        assertFalse(coordinator.canStartPrompt)
    }

    @Test
    fun interruptionMessageDoesNotClaimThatKeystoreKeyIsInvalid() {
        val message = biometricPromptTerminalMessage(
            BiometricPromptTerminalState.INTERRUPTED,
            "Canceled",
        )

        assertTrue(message.contains("打断"))
        assertTrue(message.contains("未被判定失效"))
        assertFalse(message.contains("重新绑定"))
    }

    private fun startedLease(decision: BiometricPromptStartDecision): BiometricPromptLease =
        (decision as BiometricPromptStartDecision.Started).lease
}
