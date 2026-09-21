import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ilab.iptv.spike"
    compileSdk = 36

    defaultConfig {
        // Different applicationId from the production package (ilab.iptv.player) so it can never
        // collide with, or overwrite, the release build QA is validating (dispatch boundary).
        applicationId = "ilab.iptv.player.spike"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-spike"
        ndk { abiFilters += "armeabi-v7a" }
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
        getByName("release") {
            isMinifyEnabled = false
            // Left unsigned here on purpose: the host signs with the repo keystore via
            // tools/spike/sign-release.sh so no credential path is baked into this build file.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
}
