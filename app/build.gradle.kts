plugins {
    id("com.android.application")
}

// Release signing comes from environment variables (set by CI from repo secrets).
// Without them, the release build falls back to the debug key so it still installs.
val ksFile = System.getenv("POPSHARE_KEYSTORE")?.let { file(it) }
val hasReleaseKey = ksFile?.exists() == true

android {
    namespace = "app.popshare"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.popshare"
        minSdk = 30
        targetSdk = 36
        versionCode = (System.getenv("POPSHARE_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("POPSHARE_VERSION_NAME") ?: "0.1.0-dev"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("POPSHARE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("POPSHARE_KEY_ALIAS")
                keyPassword = System.getenv("POPSHARE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
