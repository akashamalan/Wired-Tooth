plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0 moved the Compose compiler out of AGP into its own plugin.
    id("org.jetbrains.kotlin.plugin.compose")
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

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    // Deliberately NOT Material3. This interface follows Apple's visual
    // language, and Material brings its own type ramp, shapes, ripple and
    // colour semantics that would have to be fought at every component.
    // foundation gives layout, gestures and BasicText; the rest is ours.

    testImplementation("junit:junit:4.13.2")
}
