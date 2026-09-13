plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wiredtooth.receiver"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wiredtooth.receiver"
        // Android 8.0. AudioTrack.Builder, PERFORMANCE_MODE_LOW_LATENCY and
        // getTimestamp all exist from 26, which is what the playback path needs.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // Deliberately nothing else. No AppCompat, no Material, no Compose: the UI
    // is four numbers and a status line, and every dependency added here is
    // another thing that can fail to resolve on a machine that is offline.
    testImplementation("junit:junit:4.13.2")
}
