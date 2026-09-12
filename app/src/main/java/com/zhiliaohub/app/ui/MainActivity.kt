package com.zhiliaohub.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zhiliaohub.app.BuildConfig
import com.zhiliaohub.app.R
import com.zhiliaohub.app.ZhiliaohubApplication
import com.zhiliaohub.app.databinding.ActivityMainBinding
import com.zhiliaohub.app.network.ApiResult
import com.zhiliaohub.app.network.BackupStatus
import com.zhiliaohub.app.network.Challenge
import com.zhiliaohub.app.network.ProjectBackupStatus
import com.zhiliaohub.app.network.ZhiliaohubApi
import com.zhiliaohub.app.security.TotpGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var loginBiometricPrompt: BiometricPrompt? = null
    private var totpBiometricPrompt: BiometricPrompt? = null
    private val app: ZhiliaohubApplication
        get() = application as ZhiliaohubApplication

    private var authJob: Job? = null
    private var healthJob: Job? = null
    private var backupStatusJob: Job? = null
    private var sessionRefreshJob: Job? = null
    private var totpStorageJob: Job? = null
    private var totpDisplayJob: Job? = null
    private var activeApi: ZhiliaohubApi? = null
    private var pendingChallenge: Challenge? = null
    private var pendingTotpAction: TotpBiometricAction? = null
    private var currentTotpCode: String? = null
    private var backupCardState = BackupStatusCardState()
    private val totpDisplayGate = TotpDisplayGate()
    private val biometricPromptCoordinator = BiometricPromptCoordinator()
    private var isTotpBound = false
    private var showingLoginBiometricBusyMessage = false

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        beginAuthentication()
    }
    private val pairingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        beginAuthentication()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.versionValue.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)

        binding.settingsButton.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        binding.retryAuthButton.setOnClickListener { beginAuthentication() }
        binding.pairButton.setOnClickListener { openPairing(resetExisting = true) }
        binding.refreshHealthButton.setOnClickListener { refreshHealthAndSession() }
        binding.refreshBackupStatusButton.setOnClickListener { checkBackupStatus() }
        binding.bindTotpButton.setOnClickListener { bindTotpSecret() }
        binding.showTotpButton.setOnClickListener { promptForTotp(TotpBiometricAction.SHOW_CODE) }
        binding.toggleTotpCodeButton.setOnClickListener { toggleTotpCodeMask() }
        binding.unbindTotpButton.setOnClickListener { confirmTotpUnbind() }

        refreshTotpBindingState()
        beginAuthentication()
    }

    override fun onDestroy() {
        abandonActiveBiometricPrompt()
        authJob?.cancel()
        healthJob?.cancel()
        backupStatusJob?.cancel()
        sessionRefreshJob?.cancel()
        totpStorageJob?.cancel()
        hideTotpCode()
        super.onDestroy()
    }

    override fun onStop() {
        abandonActiveBiometricPrompt()
        hideTotpCode()
        super.onStop()
    }

    private fun beginAuthentication() {
        authJob?.cancel()
        healthJob?.cancel()
        backupStatusJob?.cancel()
        sessionRefreshJob?.cancel()
        hideTotpCode()
        pendingChallenge = null
        showAuthLoading(getString(R.string.main_message_01))
        authJob = lifecycleScope.launch {
            val settings = app.appPreferences.current()
            binding.serverValue.text = settings.serverUrl ?: getString(R.string.main_message_02)
            val serverUrl = settings.serverUrl
            if (serverUrl == null) {
                showAuthError(getString(R.string.main_message_03), canRetry = false, canPair = false)
                return@launch
            }
            if (!settings.isPaired) {
                showPairRequired(getString(R.string.main_message_04))
                return@launch
            }
            val hasSigningKey = withContext(Dispatchers.IO) { app.deviceKeyManager.hasSigningKey() }
            if (!hasSigningKey) {
                app.appPreferences.setPaired(false)
                withContext(Dispatchers.IO) { app.registrationManager.clearSession() }
                showPairRequired(getString(R.string.main_message_05))
                return@launch
            }

            val api = try {
                app.apiClientFactory.create(serverUrl)
            } catch (error: IllegalArgumentException) {
                showAuthError(getString(R.string.main_message_06), canRetry = false, canPair = false)
                return@launch
            }
            activeApi = api
            when (val session = api.checkSession()) {
                is ApiResult.Success -> showAuthenticated(api, getString(R.string.main_message_07))
                is ApiResult.HttpFailure -> {
                    if (session.statusCode == 401) requestChallenge(api)
                    else showAuthError(
                        getString(R.string.main_message_08, session.statusCode, session.message),
                        canRetry = true,
                        canPair = false,
                    )
                }
                is ApiResult.NetworkFailure -> showAuthError(
                    networkMessage(session.exception, session.automaticRetryCount),
                    canRetry = true,
                    canPair = false,
                )
                is ApiResult.ProtocolFailure -> showAuthError(session.message, canRetry = true, canPair = false)
            }
        }
    }

    private suspend fun requestChallenge(api: ZhiliaohubApi) {
        showAuthLoading(getString(R.string.main_message_09))
        when (val result = api.requestChallenge()) {
            is ApiResult.Success -> promptForSignature(result.value)
            is ApiResult.HttpFailure -> when (result.statusCode) {
                409 -> handleRevokedDevice()
                429 -> showAuthError(getString(R.string.main_message_10), true, false)
                else -> showAuthError(
                    getString(R.string.main_message_11, result.statusCode, result.message),
                    canRetry = true,
                    canPair = false,
                )
            }
            is ApiResult.NetworkFailure -> showAuthError(
                networkMessage(result.exception, result.automaticRetryCount),
                true,
                false,
            )
            is ApiResult.ProtocolFailure -> showAuthError(result.message, true, false)
        }
    }

    private fun promptForSignature(challenge: Challenge) {
        val biometricAvailability = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (biometricAvailability != BiometricManager.BIOMETRIC_SUCCESS) {
            showAuthError(
                getString(R.string.main_message_12),
                canRetry = true,
                canPair = false,
            )
            return
        }

        val lease = when (
            val decision = biometricPromptCoordinator.tryStart(BiometricPromptPurpose.LOGIN_SIGNATURE)
        ) {
            is BiometricPromptStartDecision.Started -> decision.lease
            is BiometricPromptStartDecision.Busy -> {
                showAuthError(
                    biometricPromptBusyMessage(
                        BiometricPromptPurpose.LOGIN_SIGNATURE,
                        decision.activeLease.purpose,
                    ),
                    canRetry = true,
                    canPair = false,
                )
                return
            }
        }
        updateBiometricControls()

        val signature = try {
            app.deviceKeyManager.createBiometricSignature()
        } catch (_: KeyPermanentlyInvalidatedException) {
            finishBiometricPrompt(
                lease,
                BiometricPromptTerminalState.FAILED,
            )
            lifecycleScope.launch { handleInvalidatedKey() }
            return
        } catch (_: Exception) {
            finishBiometricPrompt(
                lease,
                BiometricPromptTerminalState.FAILED,
            )
            showAuthError(getString(R.string.main_message_13), canRetry = false, canPair = true)
            return
        }

        pendingChallenge = challenge
        binding.authProgress.visibility = View.GONE
        binding.authStatus.text = getString(R.string.main_message_14)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.main_message_15))
            .setSubtitle(getString(R.string.main_message_16))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.main_message_17))
            .build()
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            biometricCallback(lease),
        )
        loginBiometricPrompt = prompt
        try {
            prompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
        } catch (_: Exception) {
            loginBiometricPrompt = null
            pendingChallenge = null
            finishBiometricPrompt(
                lease,
                BiometricPromptTerminalState.FAILED,
            )
            showAuthError(getString(R.string.main_message_18), canRetry = true, canPair = false)
        }
    }

    private fun biometricCallback(lease: BiometricPromptLease) =
        object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            super.onAuthenticationSucceeded(result)
            if (!finishBiometricPrompt(lease, BiometricPromptTerminalState.SUCCEEDED)) return
            loginBiometricPrompt = null
            val challenge = pendingChallenge.also { pendingChallenge = null }
            val signature = result.cryptoObject?.signature
            if (challenge == null || signature == null) {
                showAuthError(
                    getString(R.string.main_message_19),
                    true,
                    false,
                )
                return
            }
            val signatureBase64 = try {
                signChallenge(signature, challenge.signedPayload)
            } catch (_: Exception) {
                showAuthError(getString(R.string.main_message_20), true, true)
                return
            }
            lifecycleScope.launch { submitLogin(challenge, signatureBase64) }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            super.onAuthenticationError(errorCode, errString)
            if (!biometricPromptCoordinator.isActive(lease)) return
            loginBiometricPrompt = null
            pendingChallenge = null
            val terminalState = biometricTerminalState(errorCode)
            finishBiometricPrompt(lease, terminalState)
            showAuthError(
                biometricPromptTerminalMessage(terminalState, errString),
                canRetry = true,
                canPair = false,
            )
        }

        override fun onAuthenticationFailed() {
            super.onAuthenticationFailed()
            if (!biometricPromptCoordinator.isActive(lease)) return
            binding.authStatus.text = getString(R.string.main_message_21)
        }
    }

    private suspend fun submitLogin(challenge: Challenge, signatureBase64: String) {
        val api = activeApi ?: run {
            showAuthError(getString(R.string.main_message_22), true, false)
            return
        }
        showAuthLoading(getString(R.string.main_message_23))
        when (val result = api.login(challenge.challengeId, signatureBase64)) {
            is ApiResult.Success -> showAuthenticated(api, getString(R.string.main_message_24))
            is ApiResult.HttpFailure -> {
                if (result.statusCode == 401) probeRevocationAfterRejectedLogin(api)
                else if (result.statusCode == 429) {
                    showAuthError(getString(R.string.main_message_25), true, false)
                } else {
                    showAuthError(
                        getString(R.string.main_message_26, result.statusCode, result.message),
                        canRetry = true,
                        canPair = false,
                    )
                }
            }
            is ApiResult.NetworkFailure -> showAuthError(
                networkMessage(result.exception, result.automaticRetryCount),
                true,
                false,
            )
            is ApiResult.ProtocolFailure -> showAuthError(result.message, true, false)
        }
    }

    private suspend fun probeRevocationAfterRejectedLogin(api: ZhiliaohubApi) {
        when (val probe = api.requestChallenge()) {
            is ApiResult.HttpFailure -> {
                if (probe.statusCode == 409) handleRevokedDevice()
                else showAuthError(getString(R.string.main_message_27), true, true)
            }
            is ApiResult.Success -> showAuthError(
                getString(R.string.main_message_28),
                canRetry = true,
                canPair = true,
            )
            is ApiResult.NetworkFailure -> showAuthError(
                networkMessage(probe.exception, probe.automaticRetryCount),
                true,
                false,
            )
            is ApiResult.ProtocolFailure -> showAuthError(probe.message, true, false)
        }
    }

    private suspend fun handleRevokedDevice() {
        withContext(Dispatchers.IO) { app.registrationManager.resetPairing() }
        showPairRequired(getString(R.string.main_message_29))
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.main_message_30))
                .setMessage(getString(R.string.main_message_31))
                .setNegativeButton(getString(R.string.main_message_32), null)
                .setPositiveButton(getString(R.string.main_message_33)) { _, _ -> openPairing(resetExisting = false) }
                .show()
        }
    }

    private suspend fun handleInvalidatedKey() {
        withContext(Dispatchers.IO) { app.registrationManager.resetPairing() }
        showPairRequired(getString(R.string.main_message_34))
    }

    private fun showAuthenticated(api: ZhiliaohubApi, message: String) {
        binding.authProgress.visibility = View.GONE
        binding.authStatus.text = message
        binding.authStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
        binding.retryAuthButton.visibility = View.GONE
        binding.pairButton.visibility = View.GONE
        binding.monitorContainer.visibility = View.VISIBLE
        binding.healthCard.visibility = View.VISIBLE
        binding.backupStatusCard.visibility = View.VISIBLE
        activeApi = api
        checkHealth()
        checkBackupStatus()
        refreshTotpBindingState()
    }

    private fun refreshHealthAndSession() {
        checkHealth()
        val api = activeApi ?: return
        sessionRefreshJob?.cancel()
        sessionRefreshJob = lifecycleScope.launch {
            when (sessionRefreshDecision(api.checkSession())) {
                SessionRefreshDecision.SESSION_VALID -> {
                    binding.authStatus.text = getString(R.string.main_message_35)
                    binding.authStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                }
                SessionRefreshDecision.REAUTHENTICATE -> beginAuthentication()
                SessionRefreshDecision.CHECK_FAILED -> {
                    binding.authStatus.text = getString(R.string.main_message_36)
                    binding.authStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger))
                }
            }
        }
    }

    private fun checkHealth() {
        val api = activeApi ?: return
        healthJob?.cancel()
        binding.refreshHealthButton.isEnabled = false
        binding.healthStatus.text = getString(R.string.main_message_37)
        binding.healthStatus.setTextColor(ContextCompat.getColor(this, R.color.steel_blue_dark))
        healthJob = lifecycleScope.launch {
            val detail = when (val result = api.health()) {
                is ApiResult.Success -> {
                    if (result.value.isOnline) {
                        binding.healthStatus.text = getString(R.string.main_message_38)
                        binding.healthStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                        null
                    } else {
                        binding.healthStatus.text = getString(R.string.main_message_39)
                        binding.healthStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger))
                        getString(R.string.main_message_40, result.value.serverStatus)
                    }
                }
                is ApiResult.HttpFailure -> {
                    markHealthOffline()
                    getString(R.string.main_message_41, result.statusCode, result.message)
                }
                is ApiResult.NetworkFailure -> {
                    markHealthOffline()
                    networkMessage(result.exception, result.automaticRetryCount)
                }
                is ApiResult.ProtocolFailure -> {
                    markHealthOffline()
                    result.message
                }
            }
            val checkedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            binding.healthCheckedAt.text = buildString {
                append(getString(R.string.main_message_42))
                append(checkedAt)
                if (detail != null) {
                    append("\n")
                    append(detail)
                }
            }
            binding.refreshHealthButton.isEnabled = true
        }
    }

    private fun markHealthOffline() {
        binding.healthStatus.text = getString(R.string.main_message_43)
        binding.healthStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
    }

    private fun checkBackupStatus() {
        val api = activeApi ?: return
        backupStatusJob?.cancel()
        binding.refreshBackupStatusButton.isEnabled = false
        binding.backupFetchStatus.visibility = View.VISIBLE
        binding.backupFetchStatus.text = getString(R.string.main_message_44)
        binding.backupFetchStatus.setTextColor(ContextCompat.getColor(this, R.color.steel_blue_dark))
        backupStatusJob = lifecycleScope.launch {
            when (val result = api.backupStatus()) {
                is ApiResult.Success -> {
                    backupCardState = backupCardState.loaded(result.value)
                    renderBackupStatus(result.value)
                    binding.backupFetchStatus.visibility = View.GONE
                }
                is ApiResult.HttpFailure -> {
                    if (result.statusCode == 401) {
                        beginAuthentication()
                        return@launch
                    }
                    showBackupStatusUnavailable(
                        getString(R.string.main_message_45, result.statusCode, result.message),
                    )
                }
                is ApiResult.NetworkFailure -> showBackupStatusUnavailable(
                    getString(R.string.main_message_46, networkMessage(result.exception, result.automaticRetryCount)),
                )
                is ApiResult.ProtocolFailure -> showBackupStatusUnavailable(
                    getString(R.string.main_message_47, result.message),
                )
            }
            binding.refreshBackupStatusButton.isEnabled = true
        }
    }

    private fun renderBackupStatus(status: BackupStatus) {
        renderBackupStatusRow(
            status.zhiliaohub,
            binding.hubBackupRow,
            binding.hubBackupStatus,
            binding.hubBackupHint,
        )
        renderBackupStatusRow(
            status.zhitian,
            binding.zhitianBackupRow,
            binding.zhitianBackupStatus,
            binding.zhitianBackupHint,
        )
    }

    private fun renderBackupStatusRow(
        status: ProjectBackupStatus,
        row: View,
        statusView: TextView,
        hintView: TextView,
    ) {
        val presentation = status.status.toPresentation()
        val textColor = when (presentation.tone) {
            BackupStatusTone.DANGER -> R.color.danger
            BackupStatusTone.LOW_KEY,
            BackupStatusTone.NEUTRAL
            -> R.color.steel_blue_dark
        }
        statusView.text = presentation.label
        statusView.setTextColor(ContextCompat.getColor(this, textColor))
        hintView.text = status.hint
        hintView.setTextColor(ContextCompat.getColor(this, textColor))
        row.setBackgroundColor(
            if (presentation.tone == BackupStatusTone.DANGER) {
                ContextCompat.getColor(this, R.color.warning_background)
            } else {
                Color.TRANSPARENT
            },
        )
    }

    private fun showBackupStatusUnavailable(message: String) {
        backupCardState = backupCardState.unavailable(message)
        binding.backupFetchStatus.visibility = View.VISIBLE
        binding.backupFetchStatus.text = if (backupCardState.latest == null) {
            message
        } else {
            getString(R.string.main_message_48, message)
        }
        binding.backupFetchStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
    }

    private fun refreshTotpBindingState() {
        totpStorageJob?.cancel()
        totpStorageJob = lifecycleScope.launch {
            val isBound = try {
                withContext(Dispatchers.IO) { app.totpSecretStore.isBound() }
            } catch (_: Exception) {
                showTotpMessage(getString(R.string.main_message_49), isError = true)
                false
            }
            renderTotpBinding(isBound)
        }
    }

    private fun bindTotpSecret() {
        val editable = binding.totpSecretInput.text
        val secretInput = CharArray(editable.length) { editable[it] }
        editable.clear()
        totpStorageJob?.cancel()
        totpStorageJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { app.totpSecretStore.bind(secretInput) }
                renderTotpBinding(true)
                showTotpMessage(getString(R.string.main_message_50))
                promptForTotp(TotpBiometricAction.SHOW_CODE)
            } catch (error: IllegalArgumentException) {
                showTotpMessage(error.message ?: getString(R.string.main_message_51), isError = true)
            } catch (_: Exception) {
                showTotpMessage(getString(R.string.main_message_52), isError = true)
            } finally {
                secretInput.fill('\u0000')
            }
        }
    }

    private fun confirmTotpUnbind() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.main_message_53))
            .setMessage(R.string.totp_unbind_warning)
            .setNegativeButton(getString(R.string.main_message_54), null)
            .setPositiveButton(getString(R.string.main_message_55)) { _, _ -> promptForTotp(TotpBiometricAction.UNBIND) }
            .show()
    }

    private fun promptForTotp(action: TotpBiometricAction) {
        val availability = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            showTotpMessage(getString(R.string.main_message_56), isError = true)
            return
        }

        val lease = when (val decision = biometricPromptCoordinator.tryStart(BiometricPromptPurpose.TOTP)) {
            is BiometricPromptStartDecision.Started -> decision.lease
            is BiometricPromptStartDecision.Busy -> {
                showTotpMessage(
                    biometricPromptBusyMessage(BiometricPromptPurpose.TOTP, decision.activeLease.purpose),
                    isError = true,
                    tracksActiveLogin = decision.activeLease.purpose == BiometricPromptPurpose.LOGIN_SIGNATURE,
                )
                return
            }
        }
        updateBiometricControls()
        pendingTotpAction = action
        val title = when (action) {
            TotpBiometricAction.SHOW_CODE -> getString(R.string.main_message_57)
            TotpBiometricAction.UNBIND -> getString(R.string.main_message_58)
        }
        val subtitle = when (action) {
            TotpBiometricAction.SHOW_CODE -> getString(R.string.main_message_59)
            TotpBiometricAction.UNBIND -> getString(R.string.main_message_60)
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.main_message_61))
            .build()
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            totpBiometricCallback(lease),
        )
        totpBiometricPrompt = prompt
        try {
            prompt.authenticate(promptInfo)
        } catch (_: Exception) {
            totpBiometricPrompt = null
            pendingTotpAction = null
            finishBiometricPrompt(lease, BiometricPromptTerminalState.FAILED)
            showTotpMessage(getString(R.string.main_message_62), isError = true)
        }
    }

    private fun totpBiometricCallback(lease: BiometricPromptLease) =
        object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            super.onAuthenticationSucceeded(result)
            if (!finishBiometricPrompt(lease, BiometricPromptTerminalState.SUCCEEDED)) return
            totpBiometricPrompt = null
            when (pendingTotpAction.also { pendingTotpAction = null }) {
                TotpBiometricAction.SHOW_CODE -> startTotpDisplay()
                TotpBiometricAction.UNBIND -> clearTotpBinding()
                null -> showTotpMessage(
                    getString(R.string.main_message_63),
                    isError = true,
                )
            }
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            super.onAuthenticationError(errorCode, errString)
            if (!biometricPromptCoordinator.isActive(lease)) return
            totpBiometricPrompt = null
            pendingTotpAction = null
            val terminalState = biometricTerminalState(errorCode)
            finishBiometricPrompt(lease, terminalState)
            showTotpMessage(
                biometricPromptTerminalMessage(terminalState, errString),
                isError = true,
            )
        }

        override fun onAuthenticationFailed() {
            super.onAuthenticationFailed()
            if (!biometricPromptCoordinator.isActive(lease)) return
            showTotpMessage(getString(R.string.main_message_64), isError = true)
        }
    }

    private fun startTotpDisplay() {
        hideTotpCode()
        totpDisplayGate.unlock()
        renderTotpCode()
        updateBiometricControls()
        totpDisplayJob = lifecycleScope.launch {
            var displayedWindow = -1L
            while (isActive && totpDisplayGate.isUnlocked) {
                val nowMillis = System.currentTimeMillis()
                val nowSeconds = nowMillis / 1_000L
                val window = nowSeconds / TotpGenerator.PERIOD_SECONDS
                if (window != displayedWindow) {
                    currentTotpCode = try {
                        withContext(Dispatchers.IO) { app.totpSecretStore.codeAt(nowSeconds) }
                    } catch (_: Exception) {
                        null
                    }
                    if (currentTotpCode == null) {
                        hideTotpCode()
                        renderTotpBinding(false)
                        showTotpMessage(getString(R.string.main_message_65), isError = true)
                        return@launch
                    }
                    displayedWindow = window
                }
                renderTotpCode()
                binding.totpCountdown.text = getString(
                    R.string.totp_seconds_remaining,
                    TotpGenerator.remainingSeconds(nowSeconds),
                )
                val delayMillis = 1_000L - (nowMillis % 1_000L)
                delay(delayMillis)
            }
        }
    }

    private fun toggleTotpCodeMask() {
        if (!totpDisplayGate.toggleMask()) return
        renderTotpCode()
        updateBiometricControls()
    }

    private fun renderTotpCode() {
        binding.totpCode.text = currentTotpCode
            ?.let(totpDisplayGate::visibleCode)
            ?: getString(R.string.totp_code_hidden)
        binding.toggleTotpCodeButton.text = getString(
            if (totpDisplayGate.isMasked) R.string.totp_show_digits else R.string.totp_hide_digits,
        )
    }

    private fun hideTotpCode() {
        totpDisplayGate.lock()
        currentTotpCode = null
        totpDisplayJob?.cancel()
        totpDisplayJob = null
        if (::binding.isInitialized) {
            renderTotpCode()
            binding.totpCountdown.text = getString(R.string.totp_auth_required)
            updateBiometricControls()
        }
    }

    private fun clearTotpBinding() {
        totpStorageJob?.cancel()
        totpStorageJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { app.totpSecretStore.clear() }
                hideTotpCode()
                renderTotpBinding(false)
                showTotpMessage(getString(R.string.main_message_66))
            } catch (_: Exception) {
                showTotpMessage(getString(R.string.main_message_67), isError = true)
            }
        }
    }

    private fun renderTotpBinding(isBound: Boolean) {
        isTotpBound = isBound
        binding.totpBindingStatus.text = getString(if (isBound) R.string.totp_bound else R.string.totp_not_bound)
        binding.totpBindingStatus.setTextColor(
            ContextCompat.getColor(this, if (isBound) R.color.success else R.color.steel_blue_dark),
        )
        binding.totpBindContainer.visibility = if (isBound) View.GONE else View.VISIBLE
        binding.showTotpButton.visibility = if (isBound) View.VISIBLE else View.GONE
        binding.toggleTotpCodeButton.visibility = if (isBound) View.VISIBLE else View.GONE
        binding.unbindTotpButton.visibility = if (isBound) View.VISIBLE else View.GONE
        if (!isBound) hideTotpCode()
        updateBiometricControls()
    }

    private fun showTotpMessage(
        message: String,
        isError: Boolean = false,
        tracksActiveLogin: Boolean = false,
    ) {
        showingLoginBiometricBusyMessage = tracksActiveLogin
        binding.totpMessage.visibility = View.VISIBLE
        binding.totpMessage.text = message
        binding.totpMessage.setTextColor(
            ContextCompat.getColor(this, if (isError) R.color.danger else R.color.steel_blue_dark),
        )
    }

    private fun abandonActiveBiometricPrompt() {
        val lease = biometricPromptCoordinator.activeLease ?: return
        when (lease.purpose) {
            BiometricPromptPurpose.LOGIN_SIGNATURE -> {
                try {
                    loginBiometricPrompt?.cancelAuthentication()
                } finally {
                    loginBiometricPrompt = null
                    pendingChallenge = null
                    biometricPromptCoordinator.abandonForLifecycle()
                    updateBiometricControls()
                    showAuthError(
                        getString(R.string.main_message_68),
                        canRetry = true,
                        canPair = false,
                    )
                }
            }

            BiometricPromptPurpose.TOTP -> {
                try {
                    totpBiometricPrompt?.cancelAuthentication()
                } finally {
                    totpBiometricPrompt = null
                    pendingTotpAction = null
                    biometricPromptCoordinator.abandonForLifecycle()
                    updateBiometricControls()
                    showTotpMessage(getString(R.string.main_message_69), isError = true)
                }
            }
        }
    }

    private fun finishBiometricPrompt(
        lease: BiometricPromptLease,
        terminalState: BiometricPromptTerminalState,
    ): Boolean {
        val released = biometricPromptCoordinator.finish(lease, terminalState)
        if (released) updateBiometricControls()
        return released
    }

    private fun updateBiometricControls() {
        val canStartPrompt = biometricPromptCoordinator.canStartPrompt
        binding.bindTotpButton.isEnabled = canStartPrompt
        binding.showTotpButton.isEnabled = canStartPrompt
        binding.toggleTotpCodeButton.isEnabled = canStartPrompt && totpDisplayGate.isUnlocked
        binding.unbindTotpButton.isEnabled = canStartPrompt

        if (biometricPromptCoordinator.activePurpose == BiometricPromptPurpose.LOGIN_SIGNATURE) {
            showTotpMessage(
                getString(R.string.main_message_70),
                tracksActiveLogin = true,
            )
        } else if (showingLoginBiometricBusyMessage) {
            showingLoginBiometricBusyMessage = false
            binding.totpMessage.visibility = View.GONE
        }

        if (!isTotpBound) {
            binding.showTotpButton.isEnabled = false
            binding.toggleTotpCodeButton.isEnabled = false
            binding.unbindTotpButton.isEnabled = false
        }
    }

    private fun showAuthLoading(message: String) {
        binding.authProgress.visibility = View.VISIBLE
        binding.authStatus.text = message
        binding.authStatus.setTextColor(ContextCompat.getColor(this, R.color.ink))
        binding.retryAuthButton.visibility = View.GONE
        binding.pairButton.visibility = View.GONE
        binding.monitorContainer.visibility = View.VISIBLE
        binding.healthCard.visibility = View.GONE
        binding.backupStatusCard.visibility = View.GONE
    }

    private fun showAuthError(message: String, canRetry: Boolean, canPair: Boolean) {
        binding.authProgress.visibility = View.GONE
        binding.authStatus.text = message
        binding.authStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
        binding.retryAuthButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        binding.pairButton.visibility = if (canPair) View.VISIBLE else View.GONE
        binding.monitorContainer.visibility = View.VISIBLE
        binding.healthCard.visibility = View.GONE
        binding.backupStatusCard.visibility = View.GONE
    }

    private fun showPairRequired(message: String) {
        showAuthError(message, canRetry = false, canPair = true)
    }

    private fun openPairing(resetExisting: Boolean) {
        lifecycleScope.launch {
            if (resetExisting) withContext(Dispatchers.IO) { app.registrationManager.resetPairing() }
            pairingLauncher.launch(Intent(this@MainActivity, PairingActivity::class.java))
        }
    }

    private fun signChallenge(signature: Signature, signedPayload: String): String {
        signature.update(signedPayload.toByteArray(StandardCharsets.UTF_8))
        val derSignature = signature.sign()
        return Base64.encodeToString(derSignature, Base64.NO_WRAP)
    }
}
