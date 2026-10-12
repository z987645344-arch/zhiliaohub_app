import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

abstract class GenerateLicenseAssets : DefaultTask() {
    @get:InputFile
    abstract val reportFile: RegularFileProperty

    @get:OutputDirectory
    abstract val assetsDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val destination = assetsDirectory.file("open_source_licenses.full.json").get().asFile
        destination.parentFile.mkdirs()
        reportFile.get().asFile.copyTo(destination, overwrite = true)
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.license.report)
}

android {
    namespace = "com.zhiliaohub.app"
    compileSdk = 36

    flavorDimensions += "environment"

    defaultConfig {
        applicationId = "com.zhiliaohub.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.9.0"

    }

    productFlavors {
        create("prod") {
            dimension = "environment"
            manifestPlaceholders["appLabel"] = "知了hub"
        }
        // AGP reserves names beginning with "test" for test source sets/tasks.
        // "qa" is the installable test-app flavor exposed as com.zhiliaohub.app.test.
        create("qa") {
            dimension = "environment"
            applicationIdSuffix = ".test"
            manifestPlaceholders["appLabel"] = "知了hub·测试"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

licenseReport {
    generateCsvReport = false
    generateHtmlReport = false
    generateJsonReport = false
    generateJsonFullReport = true
    generateTextReport = false
    // Keep generated files under build/, not in source sets or a shared main asset.
    copyHtmlReportToAssets = false
    copyJsonReportToAssets = false
    copyJsonFullReportToAssets = false
}

// The plugin's default is one output directory for all variants. Isolate tasks
// as well as packaged assets so Gradle can track their producer dependencies.
afterEvaluate {
    // Apply after the plugin sets each task's default outputDir during registration.
    tasks.matching { it.name.startsWith("license") && it.name.endsWith("Report") }.configureEach {
        // LicenseReportTask is internal Kotlin API; configure its task bean property.
        setProperty("outputDir", layout.buildDirectory.dir("reports/licenses/$name").get().asFile)
    }
}

androidComponents.onVariants { variant ->
    val variantTaskName = variant.name.replaceFirstChar { it.uppercaseChar() }
    val licenseAssets = tasks.register<GenerateLicenseAssets>("generate${variantTaskName}LicenseAssets") {
        dependsOn("license${variantTaskName}Report")
        reportFile.set(layout.buildDirectory.file("reports/licenses/license${variantTaskName}Report/license${variantTaskName}Report.full.json"))
        assetsDirectory.set(layout.buildDirectory.dir("generated/licenseAssets/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(licenseAssets, GenerateLicenseAssets::assetsDirectory)
    tasks.withType<Test>().configureEach {
        if (name == "test${variantTaskName}UnitTest") {
            dependsOn(licenseAssets)
            inputs.file(licenseAssets.flatMap { it.assetsDirectory.file("open_source_licenses.full.json") })
            doFirst {
                val runtime = configurations.getByName("${variant.name}RuntimeClasspath")
                systemProperty("licenseReportFile", licenseAssets.get().assetsDirectory
                    .file("open_source_licenses.full.json").get().asFile.absolutePath)
                val directCount = runtime.resolvedConfiguration.firstLevelModuleDependencies.size
                systemProperty("licenseDirectDependencyCount", directCount)
                logger.lifecycle("License coverage ${variant.name}: direct modules=$directCount")
                systemProperty("licenseRuntimeCoordinates", runtime.resolvedConfiguration.resolvedArtifacts
                    .map { it.moduleVersion.id.toString() }.distinct().sorted().joinToString("\n"))
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.json)
}
