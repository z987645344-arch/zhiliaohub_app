package com.zhiliaohub.app.ui

import org.json.JSONObject

data class OpenSourceLicense(val name: String, val text: String?, val url: String)

data class OpenSourceComponent(
    val name: String,
    val coordinate: String,
    val licenses: List<OpenSourceLicense>,
)

object OpenSourceLicenseReport {
    const val ASSET_NAME = "open_source_licenses.full.json"

    fun parse(json: String): List<OpenSourceComponent> {
        val report = JSONObject(json)
        val texts = report.getJSONObject("license_texts")
        val dependencies = report.getJSONArray("dependencies")
        return List(dependencies.length()) { index ->
            val dependency = dependencies.getJSONObject(index)
            val licenses = dependency.getJSONArray("licenses")
            OpenSourceComponent(
                name = dependency.getString("project"),
                coordinate = dependency.getString("dependency"),
                licenses = List(licenses.length()) { licenseIndex ->
                    val license = licenses.getJSONObject(licenseIndex)
                    val key = license.optString("license_key")
                    OpenSourceLicense(
                        name = license.getString("license"),
                        text = texts.optString(key).takeIf { it.isNotBlank() },
                        url = license.optString("license_url"),
                    )
                },
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
