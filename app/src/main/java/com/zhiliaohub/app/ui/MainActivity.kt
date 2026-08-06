package com.zhiliaohub.app.ui

import android.content.Intent
import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.view.View
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
import com.zhiliaohub.app.network.Challenge
import com.zhiliaohub.app.network.ZhiliaohubApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var biometricPrompt: BiometricPrompt
    private val app: ZhiliaohubApplication
        get() = application as ZhiliaohubApplication

    private var authJob: Job? = null
    private var healthJob: Job? = null
    private var activeApi: ZhiliaohubApi? = null
    private var pendingChallenge: Challenge? = null

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

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            biometricCallback,
        )
        binding.settingsButton.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        binding.retryAuthButton.setOnClickListener { beginAuthentication() }
        binding.pairButton.setOnClickListener { openPairing(resetExisting = true) }
        binding.refreshHealthButton.setOnClickListener { checkHealth() }

        beginAuthentication()
    }

    override fun onDestroy() {
        authJob?.cancel()
        healthJob?.cancel()
        super.onDestroy()
    }

    private fun beginAuthentication() {
        authJob?.cancel()
        healthJob?.cancel()
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
                    networkMessage(session.exception),
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
            is ApiResult.NetworkFailure -> showAuthError(networkMessage(result.exception), true, false)
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

        val signature = try {
            app.deviceKeyManager.createBiometricSignature()
        } catch (_: KeyPermanentlyInvalidatedException) {
            lifecycleScope.launch { handleInvalidatedKey() }
            return
        } catch (_: Exception) {
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
        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(signature))
    }

    private val biometricCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            super.onAuthenticationSucceeded(result)
            val challenge = pendingChallenge
            val signature = result.cryptoObject?.signature
            if (challenge == null || signature == null) {
                showAuthError("生物识别成功，但未取得可用签名上下文，请重试。", true, false)
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
            showAuthError("生物识别未完成：$errString", canRetry = true, canPair = false)
        }

        override fun onAuthenticationFailed() {
            super.onAuthenticationFailed()
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
            is ApiResult.NetworkFailure -> showAuthError(networkMessage(result.exception), true, false)
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
            is ApiResult.NetworkFailure -> showAuthError(networkMessage(probe.exception), true, false)
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
        activeApi = api
        checkHealth()
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
                    "健康检查返回 HTTP ${result.statusCode}。"
                }
                is ApiResult.NetworkFailure -> {
                    markHealthOffline()
                    networkMessage(result.exception)
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

    private fun showAuthLoading(message: String) {
        binding.authProgress.visibility = View.VISIBLE
        binding.authStatus.text = message
        binding.authStatus.setTextColor(ContextCompat.getColor(this, R.color.ink))
        binding.retryAuthButton.visibility = View.GONE
        binding.pairButton.visibility = View.GONE
        binding.monitorContainer.visibility = View.GONE
    }

    private fun showAuthError(message: String, canRetry: Boolean, canPair: Boolean) {
        binding.authProgress.visibility = View.GONE
        binding.authStatus.text = message
        binding.authStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
        binding.retryAuthButton.visibility = if (canRetry) View.VISIBLE else View.GONE
        binding.pairButton.visibility = if (canPair) View.VISIBLE else View.GONE
        binding.monitorContainer.visibility = View.GONE
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
