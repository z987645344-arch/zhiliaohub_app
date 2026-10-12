package com.zhiliaohub.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AboutAndLicensesTest {
    @Test
    fun copyrightUsesExactStaticTextWithoutTrailingPeriod() {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(projectFile("app/src/main/res/values/strings.xml"))
        val strings = document.getElementsByTagName("string")
        val copyright = (0 until strings.length).map { strings.item(it) }
            .single { it.attributes.getNamedItem("name").nodeValue == "about_copyright" }.textContent
        assertEquals("© 2026 知了 · 保留所有权利", copyright)
        assertFalse(copyright.endsWith("。") || copyright.endsWith("."))
        val activity = projectFile("app/src/main/java/com/zhiliaohub/app/ui/AboutActivity.kt").readText()
        assertTrue(activity.contains("BuildConfig.VERSION_NAME"))
    }

    @Test
    fun generatedReportIncludesAllRuntimeArtifactsAndTransitiveOkio() {
        val file = File(requireNotNull(System.getProperty("licenseReportFile")))
        assertTrue("The build must generate the report before tests", file.isFile)
        val components = OpenSourceLicenseReport.parse(file.readText())
        val direct = requireNotNull(System.getProperty("licenseDirectDependencyCount")).toInt()
        assertTrue("Transitive components must outnumber direct dependencies", components.size > direct)
        val runtime = requireNotNull(System.getProperty("licenseRuntimeCoordinates")).split("\n").toSet()
        // Multiplatform artifacts (-android/-jvm) share the root module's POM
        // license. The plugin reports that POM coordinate, not the platform jar.
        assertEquals(runtime.map(::pomCoordinate).toSet(), components.map { pomCoordinate(it.coordinate) }.toSet())
        assertTrue(components.all { it.name.isNotBlank() && it.licenses.isNotEmpty() })
        val okio = components.filter { it.coordinate.startsWith("com.squareup.okio:") }
        assertTrue(okio.isNotEmpty())
        assertTrue(okio.all { entry -> entry.licenses.any { it.name.isNotBlank() && !it.text.isNullOrBlank() } })
        assertFalse(components.any { it.coordinate.startsWith("com.google.android.gms:") })
    }

    @Test
    fun parserKeepsLibraryNamesAndOfflineLicenseTexts() {
        val json = """{
            "license_texts": {"test-license": "Test license body"},
            "dependencies": [{"project": "Test Library", "dependency": "test:library:1.0",
                "licenses": [{"license": "Test License", "license_key": "test-license",
                    "license_url": "https://example.com/license"}]}]
        }"""
        val component = OpenSourceLicenseReport.parse(json).single()
        assertEquals("Test Library", component.name)
        assertEquals("Test License", component.licenses.single().name)
        assertEquals("Test license body", component.licenses.single().text)
    }

    private fun projectFile(relativePath: String): File {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(5) {
            val candidate = File(directory, relativePath)
            if (candidate.exists()) return candidate
            directory = directory.parentFile ?: return@repeat
        }
        error("Project file not found: $relativePath")
    }

    private fun pomCoordinate(coordinate: String): String {
        val parts = coordinate.split(':')
        require(parts.size == 3)
        return "${parts[0]}:${parts[1].removeSuffix("-android").removeSuffix("-jvm")}:${parts[2]}"
    }
}
