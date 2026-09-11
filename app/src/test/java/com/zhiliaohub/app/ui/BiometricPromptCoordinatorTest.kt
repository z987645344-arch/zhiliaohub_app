package com.zhiliaohub.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricPromptCoordinatorTest {
    @Test
    fun loginPromptInFlightRejectsTotpBeforeSecondPromptStarts() {
        val coordinator = BiometricPromptCoordinator()

        assertEquals(
            BiometricPromptStartDecision.Started,
            coordinator.tryStart(BiometricPromptPurpose.LOGIN_SIGNATURE),
        )
        val decision = coordinator.tryStart(BiometricPromptPurpose.TOTP)
        var secondPromptLaunchCount = 0
        if (decision == BiometricPromptStartDecision.Started) secondPromptLaunchCount += 1

        assertEquals(
            BiometricPromptStartDecision.Busy(BiometricPromptPurpose.LOGIN_SIGNATURE),
            decision,
        )
        assertEquals(0, secondPromptLaunchCount)
        assertEquals(BiometricPromptPurpose.LOGIN_SIGNATURE, coordinator.activePurpose)
        assertFalse(coordinator.canStartPrompt)
        assertEquals(
            "登录身份验证正在进行，请先完成当前的身份验证。",
            biometricPromptBusyMessage(
                BiometricPromptPurpose.TOTP,
                (decision as BiometricPromptStartDecision.Busy).activePurpose,
            ),
        )
    }

    @Test
    fun totpPromptInFlightRejectsAutomaticLoginWithoutReplacingIt() {
        val coordinator = BiometricPromptCoordinator()

        coordinator.tryStart(BiometricPromptPurpose.TOTP)
        val decision = coordinator.tryStart(BiometricPromptPurpose.LOGIN_SIGNATURE)
        var automaticLoginPromptLaunchCount = 0
        if (decision == BiometricPromptStartDecision.Started) automaticLoginPromptLaunchCount += 1

        assertEquals(
            BiometricPromptStartDecision.Busy(BiometricPromptPurpose.TOTP),
            decision,
        )
        assertEquals(0, automaticLoginPromptLaunchCount)
        assertEquals(BiometricPromptPurpose.TOTP, coordinator.activePurpose)
        assertEquals(
            "TOTP 身份验证正在进行，请先完成当前的身份验证，再重试登录。",
            biometricPromptBusyMessage(
                BiometricPromptPurpose.LOGIN_SIGNATURE,
                (decision as BiometricPromptStartDecision.Busy).activePurpose,
            ),
        )
    }

    @Test
    fun everyTerminalOutcomeReleasesPromptForTheNextPurpose() {
        BiometricPromptTerminalState.entries.forEach { terminalState ->
            val coordinator = BiometricPromptCoordinator()
            coordinator.tryStart(BiometricPromptPurpose.LOGIN_SIGNATURE)

            assertTrue(coordinator.finish(BiometricPromptPurpose.LOGIN_SIGNATURE, terminalState))
            assertTrue(coordinator.canStartPrompt)
            assertEquals(terminalState, coordinator.lastTerminalState)
            assertEquals(
                BiometricPromptStartDecision.Started,
                coordinator.tryStart(BiometricPromptPurpose.TOTP),
            )
        }
    }

    @Test
    fun mismatchedLateCallbackCannotReleaseAnotherPrompt() {
        val coordinator = BiometricPromptCoordinator()
        coordinator.tryStart(BiometricPromptPurpose.TOTP)

        assertFalse(
            coordinator.finish(
                BiometricPromptPurpose.LOGIN_SIGNATURE,
                BiometricPromptTerminalState.INTERRUPTED,
            ),
        )
        assertEquals(BiometricPromptPurpose.TOTP, coordinator.activePurpose)
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
}
