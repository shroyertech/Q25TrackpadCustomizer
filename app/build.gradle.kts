plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "tech.shroyer.q25trackpadcustomizer"
    compileSdk = 34

    defaultConfig {
        applicationId = "tech.shroyer.q25trackpadcustomizer"
        minSdk = 21
        targetSdk = 34
        versionCode = 26
        versionName = "1.4.27"

        // The Q25 is arm64; the native touch-scroll helper is built for it only.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Native touch-scroll helper (an executable, packaged as libq25touch.so).
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // Extract native libraries to disk so the helper can be executed from there.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Fixed debug key checked into the repo, so every debug build (local or CI) is signed
    // the same way and can be installed over the previous one without uninstalling.
    // Debug-only; release builds are unaffected.
    signingConfigs {
        getByName("debug") {
            storeFile = file("ci-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}