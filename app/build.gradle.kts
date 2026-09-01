import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- Signing: reads from environment (GitHub Secrets) or local keystore.properties ----
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? = System.getenv(name) ?: keystoreProps.getProperty(name)
val ksFile = secret("KEYSTORE_FILE")?.let { file(it) } ?: rootProject.file("release.jks")
val hasSigning = ksFile.exists() && secret("KEYSTORE_PASSWORD") != null

// Play requires a unique, increasing versionCode per upload. CI supplies GITHUB_RUN_NUMBER.
val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "com.neonfall.game"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neonfall.game"
        minSdk = 24
        targetSdk = 34
        versionCode = ciBuild ?: 1
        versionName = "1.0.${ciBuild ?: 0}"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = ksFile
                storePassword = secret("KEYSTORE_PASSWORD")
                keyAlias = secret("KEY_ALIAS")
                keyPassword = secret("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
