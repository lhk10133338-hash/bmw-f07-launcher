plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hyteck.bmwf07launcher"
    compileSdk = 34

    signingConfigs {
        // Local builds in restricted environments cannot rely on ~/.android/debug.keystore.
        // This file is git-ignored; GitHub Actions uses the standard debug keystore.
        val localKeystore = rootProject.file("debug.keystore")
        if (localKeystore.exists()) {
            create("localDebug") {
                storeFile = localKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.hyteck.bmwf07launcher"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "3.4"
    }

    buildTypes {
        debug {
            if (rootProject.file("debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("localDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // v3.4: Shizuku user service, so a shell-UID process can inject touch into the panes'
        // virtual displays (IShizukuControl.aidl).
        aidl = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // v3.4: Shizuku. The user service runs with the adb shell UID, which holds INJECT_EVENTS, so
    // it can hand touches to the two virtual displays (a normal app cannot).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
