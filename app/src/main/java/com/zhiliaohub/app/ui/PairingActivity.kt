package com.zhiliaohub.app.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.zhiliaohub.app.ZhiliaohubApplication
import com.zhiliaohub.app.databinding.ActivityPairingBinding
import com.zhiliaohub.app.network.ApiResult
import com.zhiliaohub.app.network.PairingCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PairingActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPairingBinding
    private val app: ZhiliaohubApplication
        get() = application as ZhiliaohubApplication
    private var serverUrl: String? = null
    private var formattingCode = false

    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        loadSettings()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.deviceNameInput.setText(defaultDeviceName())
        binding.pairingCodeInput.doAfterTextChanged { editable ->
            if (formattingCode) return@doAfterTextChanged
            val formatted = PairingCode.format(editable?.toString().orEmpty())
            if (formatted != editable?.toString().orEmpty()) {
                formattingCode = true
                binding.pairingCodeInput.setText(formatted)
                binding.pairingCodeInput.setSelection(formatted.length)
                formattingCode = false
            }
        }
        binding.pairButton.setOnClickListener { pairDevice() }
        binding.settingsButton.setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        binding.cancelButton.setOnClickListener { finish() }

        loadSettings()
        verifyBiometricAvailability()
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            val settings = app.appPreferences.current()
            serverUrl = settings.serverUrl
            binding.serverValue.text = settings.serverUrl?.let { "服务器：$it" } ?: "尚未设置服务器地址"
            if (settings.serverUrl == null) {
                showStatus("请先设置后台服务器地址。")
            }
        }
    }

    private fun verifyBiometricAvailability(): Boolean {
        val result = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val message = when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "请先在系统设置中录入强生物识别信息，再进行设备配对。"
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "当前设备没有可用的强生物识别硬件，无法建立安全设备密钥。"
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "生物识别硬件暂时不可用，请稍后重试。"
            else -> "当前设备无法使用所需的强生物识别验证。"
        }
        binding.pairButton.isEnabled = message == null
        if (message != null) showStatus(message)
        return message == null
    }

    private fun pairDevice() {
        val currentServerUrl = serverUrl
        if (currentServerUrl == null) {
            showStatus("请先设置后台服务器地址。")
            return
        }
        if (!verifyBiometricAvailability()) return

        val pairingCode = binding.pairingCodeInput.text?.toString().orEmpty()
        if (!PairingCode.isValid(pairingCode)) {
            showStatus("配对码格式错误，应为 XXXXX-XXXXX；请检查是否输错字符。")
            return
        }
        val deviceName = binding.deviceNameInput.text?.toString()?.trim().orEmpty()
        if (deviceName.isEmpty()) {
            showStatus("请输入设备名称。")
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val result = try {
                val publicKeyPem = withContext(Dispatchers.IO) {
                    app.deviceKeyManager.getOrCreatePublicKeyPem()
                }
                app.apiClientFactory.create(currentServerUrl).pairDevice(
                    pairingCode = pairingCode,
                    deviceName = deviceName,
                    publicKeyPem = publicKeyPem,
                )
            } catch (error: Exception) {
                ApiResult.ProtocolFailure("无法生成或读取 Android Keystore 设备密钥。", error)
            }

            when (result) {
                is ApiResult.Success -> {
                    app.appPreferences.setPaired(true)
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                is ApiResult.HttpFailure -> showStatus(pairingHttpError(result))
                is ApiResult.NetworkFailure -> showStatus(networkMessage(result.exception))
                is ApiResult.ProtocolFailure -> showStatus(result.message)
            }
            setBusy(false)
        }
    }

    private fun pairingHttpError(error: ApiResult.HttpFailure): String = when (error.statusCode) {
        400 -> "设备信息或公钥未被服务器接受：${error.message}"
        401 -> "配对码不正确、已过期或已被使用。请在网页后台重新生成配对码后再试。"
        429 -> "尝试次数过多，服务器已临时限流，请稍后再试。"
        in 500..599 -> "后台服务暂时异常（HTTP ${error.statusCode}），请稍后重试。"
        else -> "服务器拒绝配对（HTTP ${error.statusCode}）：${error.message}"
    }

    private fun showStatus(message: String) {
        binding.statusMessage.text = message
        binding.statusMessage.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.pairButton.isEnabled = !busy
        binding.settingsButton.isEnabled = !busy
        binding.pairingCodeInput.isEnabled = !busy
        binding.deviceNameInput.isEnabled = !busy
        if (busy) {
            binding.statusMessage.text = "正在安全配对…"
            binding.statusMessage.visibility = View.VISIBLE
        }
    }

    private fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.trim().replaceFirstChar { it.uppercase() }
        return "$manufacturer ${Build.MODEL}".trim().take(100)
    }
}
