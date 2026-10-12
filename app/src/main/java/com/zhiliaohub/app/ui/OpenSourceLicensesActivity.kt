package com.zhiliaohub.app.ui

import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.zhiliaohub.app.R
import com.zhiliaohub.app.databinding.ActivityOpenSourceLicensesBinding
import com.zhiliaohub.app.databinding.ItemOpenSourceLicenseBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OpenSourceLicensesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityOpenSourceLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.backButton.setOnClickListener { finish() }
        lifecycleScope.launch {
            val components = withContext(Dispatchers.IO) {
                runCatching {
                    assets.open(OpenSourceLicenseReport.ASSET_NAME).bufferedReader().use {
                        OpenSourceLicenseReport.parse(it.readText())
                    }
                }.getOrNull()
            }
            if (components.isNullOrEmpty()) {
                binding.listStatus.setText(R.string.open_source_licenses_unavailable)
                return@launch
            }
            binding.listStatus.text = getString(R.string.open_source_licenses_count, components.size)
            for (component in components) {
                val item = ItemOpenSourceLicenseBinding.inflate(layoutInflater, binding.licenseList, false)
                item.libraryName.text = component.name
                item.libraryCoordinate.text = component.coordinate
                item.libraryLicenses.text = component.licenses
                    .joinToString(getString(R.string.open_source_license_separator)) { it.name }
                    .ifEmpty { getString(R.string.open_source_license_unknown) }
                item.detailsButton.visibility = if (component.licenses.isEmpty()) View.GONE else View.VISIBLE
                item.detailsButton.setOnClickListener { showLicense(component) }
                binding.licenseList.addView(item.root)
            }
        }
    }

    private fun showLicense(component: OpenSourceComponent) {
        val padding = resources.getDimensionPixelSize(R.dimen.space_lg)
        val text = TextView(this).apply {
            setPadding(padding, padding, padding, padding)
            setTextIsSelectable(true)
            this.text = component.licenses.joinToString("\n\n") { license ->
                "${license.name}\n\n${license.text ?: getString(R.string.open_source_license_unbundled, license.url)}"
            }
        }
        AlertDialog.Builder(this)
            .setTitle(component.name)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(R.string.close, null)
            .show()
    }
}
