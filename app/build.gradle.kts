import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Convention plugin: applies KSP + Hilt and pulls in hilt-android (`implementation`) and
    // hilt-compiler (`ksp`). The Hilt Gradle plugin alone does NOT add those dependencies, so
    // applying the raw plugin aliases here would leave @HiltAndroidApp/@AndroidEntryPoint unresolved.
    id("iptv.hilt")
}

// Release signing config (docs/01 ADR-003): the keystore FILE is committed, the PASSWORDS are not.
// Passwords come from local.properties (git-ignored) or the environment — never from the repo.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun credential(key: String): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(key)?.takeIf { it.isNotBlank() }

android {
    namespace = "ilab.iptv.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "ilab.iptv.player"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Target device is armeabi-v7a only (docs/01). Do not pull 64-bit-only native libs.
        ndk { abiFilters += "armeabi-v7a" }
    }

    signingConfigs {
        create("release") {
            credential("RELEASE_STORE_FILE")?.let { storeFile = rootProject.file(it) }
            storePassword = credential("RELEASE_STORE_PASSWORD")
            keyAlias = credential("RELEASE_KEY_ALIAS")
            keyPassword = credential("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Only sign when the keystore file is actually present, so a machine without
            // local.properties still gets an (unsigned) release APK instead of a hard failure.
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:log"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))
    implementation(project(":core:design"))

    implementation(project(":feature:channels"))
    implementation(project(":feature:player"))
    implementation(project(":feature:epg"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:wizard"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.leanback)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    debugImplementation(project(":core:testing"))
}
