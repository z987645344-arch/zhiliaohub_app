package com.zhiliaohub.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.zhiliaohub.app.BuildConfig
import com.zhiliaohub.app.R
import com.zhiliaohub.app.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.applicationName.text = applicationInfo.loadLabel(packageManager)
        binding.version.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        binding.licensesButton.setOnClickListener {
            startActivity(Intent(this, OpenSourceLicensesActivity::class.java))
        }
        binding.backButton.setOnClickListener { finish() }
    }
}
