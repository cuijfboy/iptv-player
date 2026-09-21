import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin for Android **library** modules (`:core:*` that touch Android, `:feature:*`).
 *
 * Shared baseline (docs/02 §14): compileSdk 36, minSdk 21, Java/Kotlin target 17, lint abortOnError.
 * `namespace` is derived from the module path so every module stays consistent, e.g.
 * `:core:database` -> `ilab.iptv.player.core.database`.
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ilab.iptv.player" + path.replace(':', '.').replace('-', '_')
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("testImplementation", catalog.findLibrary("junit").get())
    add("testImplementation", catalog.findLibrary("truth").get())
    add("testImplementation", catalog.findLibrary("turbine").get())
}
