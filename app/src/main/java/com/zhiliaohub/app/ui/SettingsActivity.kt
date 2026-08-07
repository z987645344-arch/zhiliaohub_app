package com.zhiliaohub.app.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.zhiliaohub.app.ZhiliaohubApplication
import com.zhiliaohub.app.databinding.ActivitySettingsBinding
import com.zhiliaohub.app.network.ServerAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val app: ZhiliaohubApplication
        get() = application as ZhiliaohubApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.serverUrlInput.doAfterTextChanged { updateAddressFeedback() }
        binding.saveButton.setOnClickListener { saveAddress() }
        binding.clearSessionButton.setOnClickListener {
            setBusy(true)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { app.registrationManager.clearSession() }
                Toast.makeText(
                    this@SettingsActivity,
                    "会话 Cookie 已清除；下次进入将使用生物识别重新登录。",
                    Toast.LENGTH_LONG,
                ).show()
                setResult(Activity.RESULT_OK)
                setBusy(false)
            }
        }
        binding.resetPairingButton.setOnClickListener { confirmResetPairing() }
        binding.cancelButton.setOnClickListener { finish() }

        lifecycleScope.launch {
            val settings = app.appPreferences.current()
            binding.serverUrlInput.setText(settings.serverUrl.orEmpty())
            updateAddressFeedback()
        }
    }

    private fun updateAddressFeedback() {
        val raw = binding.serverUrlInput.text?.toString().orEmpty()
        val parsed = runCatching { ServerAddress.parse(raw) }.getOrNull()
        val cleartext = parsed?.isCleartext == true || raw.trim().startsWith("http://", ignoreCase = true)
        binding.httpWarningContainer.visibility = if (cleartext) View.VISIBLE else View.GONE
        if (!cleartext) binding.httpAcknowledgement.isChecked = false
        binding.urlError.visibility = View.GONE
    }

    private fun saveAddress() {
        val address = try {
            ServerAddress.parse(binding.serverUrlInput.text?.toString().orEmpty())
        } catch (error: IllegalArgumentException) {
            showUrlError(error.message ?: "服务器地址无效。")
            return
        }
        if (address.isCleartext && !binding.httpAcknowledgement.isChecked) {
            showUrlError("使用 HTTP 开发地址前，请先勾选明文连接风险确认。")
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            app.appPreferences.setServerUrl(address.normalized)
            setResult(Activity.RESULT_OK)
            Toast.makeText(
                this@SettingsActivity,
                if (address.isCleartext) {
                    "开发地址已保存；当前连接未加密。已有配对和设备密钥保持不变。"
                } else {
                    "HTTPS 服务器地址已保存；已有配对和设备密钥保持不变。"
                },
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    private fun confirmResetPairing() {
        AlertDialog.Builder(this)
            .setTitle("清除本机配对？")
            .setMessage("这会清除会话、删除 Keystore 中的设备签名密钥，并要求使用网页后台的新配对码。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清除") { _, _ ->
                setBusy(true)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.registrationManager.resetPairing() }
                    setResult(Activity.RESULT_OK)
                    Toast.makeText(this@SettingsActivity, "本机配对已清除。", Toast.LENGTH_LONG).show()
                    setBusy(false)
                }
            }
            .show()
    }

    private fun showUrlError(message: String) {
        binding.urlError.text = message
        binding.urlError.visibility = View.VISIBLE
    }

    private fun setBusy(busy: Boolean) {
        binding.serverUrlInput.isEnabled = !busy
        binding.saveButton.isEnabled = !busy
        binding.clearSessionButton.isEnabled = !busy
        binding.resetPairingButton.isEnabled = !busy
    }
}
