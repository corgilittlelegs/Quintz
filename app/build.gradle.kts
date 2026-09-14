import java.io.ByteArrayOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.quintz.wifi"
    compileSdk = 35

    val appVersionName: String = (project.findProperty("versionName") as String?)
        ?: System.getenv("VERSION_NAME")
        ?: runCatching {
            val stdout = ByteArrayOutputStream()
            rootProject.exec {
                commandLine("git", "describe", "--tags", "--always")
                standardOutput = stdout
            }
            stdout.toString().trim().removePrefix("v")
        }.getOrNull()
        ?.ifEmpty { null }
        ?: "1.0.0"

    val appVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull()
        ?: System.getenv("VERSION_CODE")?.toIntOrNull()
        ?: runCatching {
            val stdout = ByteArrayOutputStream()
            rootProject.exec {
                commandLine("git", "rev-list", "--count", "HEAD")
                standardOutput = stdout
            }
            stdout.toString().trim().toInt()
        }.getOrNull()
        ?: 1

    defaultConfig {
        applicationId = "com.quintz.wifi"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Compose BOM & Material 3
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-text-google-fonts")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Shizuku API
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Security Crypto for EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
