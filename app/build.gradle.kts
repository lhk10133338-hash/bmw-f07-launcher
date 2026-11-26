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
        versionCode = 9
        versionName = "4.0"
    }

    /**
     * v4.0: two installations of the same launcher, because the virtual-display engine needs
     * platform privileges that an ordinary app cannot hold (see the CarCar analysis in README):
     *
     *  - `standard`  — no sharedUserId. Creates its trusted displays and injects touches through
     *                  Shizuku (adb shell identity), and falls back to the accessibility gesture
     *                  and the free-form engine. Installs on any phone/tablet, fold 8 included.
     *  - `systemuid` — `android:sharedUserId="android.uid.system"`, the way CarCar Launcher
     *                  ships. Direct DisplayManager / IWindowManager access. Only installs on a
     *                  unit whose ROM signs it with the platform key (the MEKEDE car unit).
     */
    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
        }
        create("systemuid") {
            dimension = "distribution"
            // A separate application id, so both APKs can sit on the same unit without fighting
            // over one package (the sharedUid itself still requires the platform signature).
            applicationIdSuffix = ".systemuid"
            versionNameSuffix = "-systemuid"
        }
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

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // sharedUserId is deprecated for new apps but is exactly what the platform-signed build
        // needs; CarCar Launcher ships the same way.
        disable += "SharedUidUsageByUid"
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

/**
 * With two flavors Android Gradle Plugin no longer creates a single `lintDebug` task (the
 * candidates are `lintStandardDebug` and `lintSystemuidDebug`). This aggregate keeps the familiar
 * command working and lints both debug variants.
 */
tasks.register("lintDebug") {
    group = "verification"
    description = "Runs lint on every debug variant (standard and systemuid)."
    dependsOn("lintStandardDebug", "lintSystemuidDebug")
}
