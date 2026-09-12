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
import com.zhiliaohub.app.R
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
            binding.serverValue.text = settings.serverUrl?.let { getString(R.string.pairing_message_01, it) } ?: getString(R.string.pairing_message_02)
            if (settings.serverUrl == null) {
                showStatus(getString(R.string.pairing_message_03))
            }
        }
    }

    private fun verifyBiometricAvailability(): Boolean {
        val result = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val message = when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> null
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> getString(R.string.pairing_message_04)
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> getString(R.string.pairing_message_05)
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> getString(R.string.pairing_message_06)
            else -> getString(R.string.pairing_message_07)
        }
        binding.pairButton.isEnabled = message == null
        if (message != null) showStatus(message)
        return message == null
    }

    private fun pairDevice() {
        val currentServerUrl = serverUrl
        if (currentServerUrl == null) {
            showStatus(getString(R.string.pairing_message_08))
            return
        }
        if (!verifyBiometricAvailability()) return

        val pairingCode = binding.pairingCodeInput.text?.toString().orEmpty()
        if (!PairingCode.isValid(pairingCode)) {
            showStatus(getString(R.string.pairing_message_09))
            return
        }
        val deviceName = binding.deviceNameInput.text?.toString()?.trim().orEmpty()
        if (deviceName.isEmpty()) {
            showStatus(getString(R.string.pairing_message_10))
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
                ApiResult.ProtocolFailure(getString(R.string.pairing_message_11), error)
            }

            when (result) {
                is ApiResult.Success -> {
                    app.appPreferences.setPaired(true)
                    setResult(Activity.RESULT_OK)
                    finish()
                }
                is ApiResult.HttpFailure -> showStatus(pairingHttpError(result))
                is ApiResult.NetworkFailure -> showStatus(
                    networkMessage(result.exception, result.automaticRetryCount),
                )
                is ApiResult.ProtocolFailure -> showStatus(result.message)
            }
            setBusy(false)
        }
    }

    private fun pairingHttpError(error: ApiResult.HttpFailure): String = when (error.statusCode) {
        400 -> getString(R.string.pairing_message_12, error.message)
        401 -> getString(R.string.pairing_message_13)
        429 -> getString(R.string.pairing_message_14)
        in 500..599 -> getString(R.string.pairing_message_15, error.statusCode)
        else -> getString(R.string.pairing_message_16, error.statusCode, error.message)
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
            binding.statusMessage.text = getString(R.string.pairing_message_17)
            binding.statusMessage.visibility = View.VISIBLE
        }
    }

    private fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.trim().replaceFirstChar { it.uppercase() }
        return "$manufacturer ${Build.MODEL}".trim().take(100)
    }
}
