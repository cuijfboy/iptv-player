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
// Passwords come from <repo-root>/local.properties (git-ignored) or the environment — never from
// the repo. This is the ONLY file read; `keystore/local.properties` is not consulted (CR-07).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun credential(key: String): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(key)?.takeIf { it.isNotBlank() }

val releaseStoreFileValue = credential("RELEASE_STORE_FILE")
val releaseStorePasswordValue = credential("RELEASE_STORE_PASSWORD")
val releaseKeyAliasValue = credential("RELEASE_KEY_ALIAS")
val releaseKeyPasswordValue = credential("RELEASE_KEY_PASSWORD")
val releaseKeystore: java.io.File? = releaseStoreFileValue?.let { rootProject.file(it) }?.takeIf { it.isFile }

// CR-07: a release build must be signed or fail. The old behaviour — silently emitting an UNSIGNED
// app-release.apk while the build reports SUCCESS — is the one thing worse than a red build, because
// the artifact looks shippable. Debug keeps its automatic debug signing.
val releaseSigningReady: Boolean =
    releaseKeystore != null &&
        releaseStorePasswordValue != null &&
        releaseKeyAliasValue != null &&
        releaseKeyPasswordValue != null

android {
    namespace = "ilab.iptv.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "ilab.iptv.player"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        // Target device is armeabi-v7a only (docs/01). Do not pull 64-bit-only native libs.
        ndk { abiFilters += "armeabi-v7a" }
    }

    signingConfigs {
        create("release") {
            storeFile = releaseKeystore
            storePassword = releaseStorePasswordValue
            keyAlias = releaseKeyAliasValue
            keyPassword = releaseKeyPasswordValue
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) {
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

// Gate every release packaging/assembly task on real credentials (CR-07). The failure names what is
// missing and where to put it instead of producing an unsigned APK.
tasks.register("requireReleaseSigning") {
    group = "verification"
    description = "Fails a release build when the release signing credentials are missing (CR-07)."
    doLast {
        if (releaseSigningReady) return@doLast
        val missing = buildList {
            when {
                releaseStoreFileValue == null -> add("RELEASE_STORE_FILE")
                releaseKeystore == null -> add("RELEASE_STORE_FILE (${releaseStoreFileValue} not found)")
            }
            if (releaseStorePasswordValue == null) add("RELEASE_STORE_PASSWORD")
            if (releaseKeyAliasValue == null) add("RELEASE_KEY_ALIAS")
            if (releaseKeyPasswordValue == null) add("RELEASE_KEY_PASSWORD")
        }
        throw GradleException(
            "Release signing is not configured, refusing to build an unsigned release APK (CR-07).\n" +
                "  missing: ${missing.joinToString(", ")}\n" +
                "  put them in ${rootProject.file("local.properties")} (git-ignored) or export them " +
                "as environment variables — see keystore/README.md.\n" +
                "  debug builds are unaffected.",
        )
    }
}

// Only the tasks that actually produce a shippable release artifact (and the signing steps inside
// them). Deliberately NOT a `*Release*` wildcard: it would also catch lint/unit-test helpers such as
// `packageReleaseResources` and break `./gradlew check` on a machine without credentials.
val releaseArtifactTasks = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "packageReleaseBundle",
    "packageReleaseUniversalApk",
    "signReleaseBundle",
)
tasks.matching { it.name in releaseArtifactTasks }.configureEach { dependsOn("requireReleaseSigning") }

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
    // P2-5: the scheduled refresh (WorkManager long-running worker + foreground notification).
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    // The refresh coordinator/scheduler tests drive coroutines on a virtual clock.
    testImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(project(":core:testing"))
}
