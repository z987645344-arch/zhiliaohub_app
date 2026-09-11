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
        showAuthLoading("正在检查本地配对与会话…")
        authJob = lifecycleScope.launch {
            val settings = app.appPreferences.current()
            binding.serverValue.text = settings.serverUrl ?: "尚未设置服务器"
            val serverUrl = settings.serverUrl
            if (serverUrl == null) {
                showAuthError("请先设置后台服务器地址。", canRetry = false, canPair = false)
                return@launch
            }
            if (!settings.isPaired) {
                showPairRequired("当前设备尚未配对，请输入网页后台生成的一次性配对码。")
                return@launch
            }
            val hasSigningKey = withContext(Dispatchers.IO) { app.deviceKeyManager.hasSigningKey() }
            if (!hasSigningKey) {
                app.appPreferences.setPaired(false)
                withContext(Dispatchers.IO) { app.registrationManager.clearSession() }
                showPairRequired("本机设备密钥不存在或已丢失，需要重新配对。")
                return@launch
            }

            val api = try {
                app.apiClientFactory.create(serverUrl)
            } catch (error: IllegalArgumentException) {
                showAuthError("已保存的服务器地址无效，请重新设置。", canRetry = false, canPair = false)
                return@launch
            }
            activeApi = api
            when (val session = api.checkSession()) {
                is ApiResult.Success -> showAuthenticated(api, "现有会话仍有效，已免生物识别登录。")
                is ApiResult.HttpFailure -> {
                    if (session.statusCode == 401) requestChallenge(api)
                    else showAuthError(
                        "会话检查失败（HTTP ${session.statusCode}）：${session.message}",
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
        showAuthLoading("会话已失效，正在申请设备登录挑战…")
        when (val result = api.requestChallenge()) {
            is ApiResult.Success -> promptForSignature(result.value)
            is ApiResult.HttpFailure -> when (result.statusCode) {
                409 -> handleRevokedDevice()
                429 -> showAuthError("设备认证请求过多，服务器已临时限流，请稍后重试。", true, false)
                else -> showAuthError(
                    "无法获取登录挑战（HTTP ${result.statusCode}）：${result.message}",
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
                "强生物识别不可用。请确认系统已录入指纹或其他强生物识别信息。",
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
            showAuthError("无法访问设备签名密钥，需要重新配对。", canRetry = false, canPair = true)
            return
        }

        pendingChallenge = challenge
        binding.authProgress.visibility = View.GONE
        binding.authStatus.text = "请完成生物识别，以授权本次登录签名。"
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("确认登录知了hub")
            .setSubtitle("验证后仅签名本次服务器挑战，私钥不会离开设备")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("取消")
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
            showAuthError("无法启动登录身份验证，请重试。", canRetry = true, canPair = false)
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
                    "生物识别成功，但登录签名上下文已被系统打断，请重试；设备密钥未被判定失效。",
                    true,
                    false,
                )
                return
            }
            val signatureBase64 = try {
                signChallenge(signature, challenge.signedPayload)
            } catch (_: Exception) {
                showAuthError("设备签名失败，请重试；若持续失败请重新配对。", true, true)
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
            binding.authStatus.text = "未识别，请重试生物识别。"
        }
    }

    private suspend fun submitLogin(challenge: Challenge, signatureBase64: String) {
        val api = activeApi ?: run {
            showAuthError("登录上下文已失效，请重新开始。", true, false)
            return
        }
        showAuthLoading("生物识别已通过，正在提交设备签名…")
        when (val result = api.login(challenge.challengeId, signatureBase64)) {
            is ApiResult.Success -> showAuthenticated(api, "设备挑战应答登录成功，会话已安全保存。")
            is ApiResult.HttpFailure -> {
                if (result.statusCode == 401) probeRevocationAfterRejectedLogin(api)
                else if (result.statusCode == 429) {
                    showAuthError("设备认证请求过多，服务器已临时限流，请稍后重试。", true, false)
                } else {
                    showAuthError(
                        "设备登录失败（HTTP ${result.statusCode}）：${result.message}",
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
                else showAuthError("设备签名被拒绝，请重新尝试登录。", true, true)
            }
            is ApiResult.Success -> showAuthError(
                "设备签名未被接受。挑战可能已过期，或本设备已被另一台设备替换。请重试；若持续失败请重新配对。",
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
        showPairRequired("当前设备已被吊销，请在网页后台重新生成配对码。")
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this)
                .setTitle("设备需要重新配对")
                .setMessage("当前设备已被吊销，请在网页后台重新生成配对码。旧会话和旧设备密钥已从本机清除。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("前往配对") { _, _ -> openPairing(resetExisting = false) }
                .show()
        }
    }

    private suspend fun handleInvalidatedKey() {
        withContext(Dispatchers.IO) { app.registrationManager.resetPairing() }
        showPairRequired("生物识别信息发生变化，Android 已使设备密钥失效。请重新配对。")
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
                    binding.authStatus.text = "会话复核通过，当前设备仍有效。"
                    binding.authStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                }
                SessionRefreshDecision.REAUTHENTICATE -> beginAuthentication()
                SessionRefreshDecision.CHECK_FAILED -> {
                    binding.authStatus.text = "健康检查已执行，但认证会话暂时无法复核。"
                    binding.authStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger))
                }
            }
        }
    }

    private fun checkHealth() {
        val api = activeApi ?: return
        healthJob?.cancel()
        binding.refreshHealthButton.isEnabled = false
        binding.healthStatus.text = "检查中…"
        binding.healthStatus.setTextColor(ContextCompat.getColor(this, R.color.steel_blue_dark))
        healthJob = lifecycleScope.launch {
            val detail = when (val result = api.health()) {
                is ApiResult.Success -> {
                    if (result.value.isOnline) {
                        binding.healthStatus.text = "在线"
                        binding.healthStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.success))
                        null
                    } else {
                        binding.healthStatus.text = "离线"
                        binding.healthStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.danger))
                        "服务返回状态：${result.value.serverStatus}"
                    }
                }
                is ApiResult.HttpFailure -> {
                    markHealthOffline()
                    "健康检查失败（HTTP ${result.statusCode}）：${result.message}"
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
                append("最近检查：")
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
        binding.healthStatus.text = "离线"
        binding.healthStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
    }

    private fun checkBackupStatus() {
        val api = activeApi ?: return
        backupStatusJob?.cancel()
        binding.refreshBackupStatusButton.isEnabled = false
        binding.backupFetchStatus.visibility = View.VISIBLE
        binding.backupFetchStatus.text = "正在获取备份状态…"
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
                        "取不到备份状态（HTTP ${result.statusCode}）：${result.message}",
                    )
                }
                is ApiResult.NetworkFailure -> showBackupStatusUnavailable(
                    "取不到备份状态：${networkMessage(result.exception, result.automaticRetryCount)}",
                )
                is ApiResult.ProtocolFailure -> showBackupStatusUnavailable(
                    "取不到备份状态：${result.message}",
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
            "取不到最新状态，已保留上次结果。\n$message"
        }
        binding.backupFetchStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
    }

    private fun refreshTotpBindingState() {
        totpStorageJob?.cancel()
        totpStorageJob = lifecycleScope.launch {
            val isBound = try {
                withContext(Dispatchers.IO) { app.totpSecretStore.isBound() }
            } catch (_: Exception) {
                showTotpMessage("无法读取本机 TOTP 绑定状态。", isError = true)
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
                showTotpMessage("绑定成功。请完成生物识别，并立即与腾讯验证器核对一次。")
                promptForTotp(TotpBiometricAction.SHOW_CODE)
            } catch (error: IllegalArgumentException) {
                showTotpMessage(error.message ?: "密钥不是有效的 Base32 内容。", isError = true)
            } catch (_: Exception) {
                showTotpMessage("无法安全保存 TOTP 密钥，请重试。", isError = true)
            } finally {
                secretInput.fill('\u0000')
            }
        }
    }

    private fun confirmTotpUnbind() {
        AlertDialog.Builder(this)
            .setTitle("解除本机 TOTP 绑定")
            .setMessage(R.string.totp_unbind_warning)
            .setNegativeButton("取消", null)
            .setPositiveButton("继续") { _, _ -> promptForTotp(TotpBiometricAction.UNBIND) }
            .show()
    }

    private fun promptForTotp(action: TotpBiometricAction) {
        val availability = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            showTotpMessage("强生物识别不可用，不能显示或清除本机 TOTP 密钥。", isError = true)
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
            TotpBiometricAction.SHOW_CODE -> "显示后台动态验证码"
            TotpBiometricAction.UNBIND -> "确认解除本机绑定"
        }
        val subtitle = when (action) {
            TotpBiometricAction.SHOW_CODE -> "验证通过后才会在屏幕上显示 6 位码"
            TotpBiometricAction.UNBIND -> "只清除本机密钥，不影响后台或腾讯验证器"
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("取消")
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
            showTotpMessage("无法启动 TOTP 身份验证，请重试。", isError = true)
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
                    "TOTP 身份验证被系统或其他验证流程打断，请重试；本机密钥未被判定失效。",
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
            showTotpMessage("未识别，请重试生物识别。", isError = true)
        }
    }

    private fun startTotpDisplay() {
        hideTotpCode()
        totpDisplayGate.unlock()
        totpDisplayJob = lifecycleScope.launch {
            var displayedWindow = -1L
            var currentCode: String? = null
            while (isActive && totpDisplayGate.isUnlocked) {
                val nowMillis = System.currentTimeMillis()
                val nowSeconds = nowMillis / 1_000L
                val window = nowSeconds / TotpGenerator.PERIOD_SECONDS
                if (window != displayedWindow) {
                    currentCode = try {
                        withContext(Dispatchers.IO) { app.totpSecretStore.codeAt(nowSeconds) }
                    } catch (_: Exception) {
                        null
                    }
                    if (currentCode == null) {
                        hideTotpCode()
                        renderTotpBinding(false)
                        showTotpMessage("本机 TOTP 密钥不可用，请重新绑定。", isError = true)
                        return@launch
                    }
                    displayedWindow = window
                }
                binding.totpCode.text = currentCode
                    ?.let(totpDisplayGate::visibleCode)
                    ?: getString(R.string.totp_code_hidden)
                binding.totpCountdown.text = getString(
                    R.string.totp_seconds_remaining,
                    TotpGenerator.remainingSeconds(nowSeconds),
                )
                val delayMillis = 1_000L - (nowMillis % 1_000L)
                delay(delayMillis)
            }
        }
    }

    private fun hideTotpCode() {
        totpDisplayGate.lock()
        totpDisplayJob?.cancel()
        totpDisplayJob = null
        if (::binding.isInitialized) {
            binding.totpCode.text = getString(R.string.totp_code_hidden)
            binding.totpCountdown.text = getString(R.string.totp_auth_required)
        }
    }

    private fun clearTotpBinding() {
        totpStorageJob?.cancel()
        totpStorageJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { app.totpSecretStore.clear() }
                hideTotpCode()
                renderTotpBinding(false)
                showTotpMessage("本机 TOTP 密钥已清除；后台与腾讯验证器没有变化。")
            } catch (_: Exception) {
                showTotpMessage("无法完整清除本机 TOTP 密钥，请重试。", isError = true)
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
                        "界面已离开，登录身份验证已取消；返回后请重新发起登录。",
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
                    showTotpMessage("界面已离开，TOTP 身份验证已取消；请重新操作。", isError = true)
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
        binding.unbindTotpButton.isEnabled = canStartPrompt

        if (biometricPromptCoordinator.activePurpose == BiometricPromptPurpose.LOGIN_SIGNATURE) {
            showTotpMessage(
                "登录身份验证正在进行，请先完成当前的身份验证。",
                tracksActiveLogin = true,
            )
        } else if (showingLoginBiometricBusyMessage) {
            showingLoginBiometricBusyMessage = false
            binding.totpMessage.visibility = View.GONE
        }

        if (!isTotpBound) {
            binding.showTotpButton.isEnabled = false
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
