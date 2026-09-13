import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.zhiliaohub.app"
    compileSdk = 36

    flavorDimensions += "environment"

    defaultConfig {
        applicationId = "com.zhiliaohub.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.7.0"

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
