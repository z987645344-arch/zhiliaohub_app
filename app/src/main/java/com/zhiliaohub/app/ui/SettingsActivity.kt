package com.zhiliaohub.app.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.zhiliaohub.app.R
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
                    getString(R.string.settings_message_01),
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
            showUrlError(error.message ?: getString(R.string.settings_message_02))
            return
        }
        if (address.isCleartext && !binding.httpAcknowledgement.isChecked) {
            showUrlError(getString(R.string.settings_message_03))
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            app.appPreferences.setServerUrl(address.normalized)
            setResult(Activity.RESULT_OK)
            Toast.makeText(
                this@SettingsActivity,
                if (address.isCleartext) {
                    getString(R.string.settings_message_04)
                } else {
                    getString(R.string.settings_message_05)
                },
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    private fun confirmResetPairing() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.settings_message_06))
            .setMessage(getString(R.string.settings_message_07))
            .setNegativeButton(getString(R.string.settings_message_08), null)
            .setPositiveButton(getString(R.string.settings_message_09)) { _, _ ->
                setBusy(true)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { app.registrationManager.resetPairing() }
                    setResult(Activity.RESULT_OK)
                    Toast.makeText(this@SettingsActivity, getString(R.string.settings_message_10), Toast.LENGTH_LONG).show()
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
