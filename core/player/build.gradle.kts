plugins {
    id("iptv.android.library")
    id("iptv.hilt")
    // P1-3 acceptance asks for >=80% coverage of the purifiable half of the engine; this is how the
    // number is produced instead of asserted (docs/05 §12.4).
    id("jacoco")
}

android {
    testOptions {
        unitTests.all { test ->
            test.extensions.configure(JacocoTaskExtension::class.java) {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:log"))
    implementation(libs.media3.exoplayer)
    // docs/02 §7.1: the main engine is `media3-exoplayer` + `-hls` + `-ui` + `-session`.
    // `-hls` is the one that must live here (HLS is a playback capability, not a UI concern);
    // `-ui`/`-session` belong to :feature:player / the foreground service (P1-4, P1-7).
    implementation(libs.media3.exoplayer.hls)
    // §4.5 C2: the engine serializes on a single-parallelism dispatcher; the engine module owns
    // its coroutine plumbing (Flows, Mutex, withContext). `-android` so a Handler-backed
    // dispatcher can serve as the ExoPlayer application looper.
    implementation(libs.kotlinx.coroutines.android)

    // P3-3 item 3: the OverscanSettings persistence is a real SharedPreferences round trip, so the
    // module's unit tests need Robolectric (same shape as `:core:data`'s Room tests). Test-only, and
    // it does not touch the engine's own JVM-testable surface.
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
}

/**
 * Coverage of the unit-testable engine code: the state machine, the error classification, the
 * parameter/aspect/audio mappings, the selector and the telemetry bridge. `Media3Engine` itself is
 * Android/ExoPlayer-bound and is proven on the device (docs/05 §12.3), not here — including it would
 * report a fake 0% for code no JVM test can reach.
 */
val pureClassPatterns = listOf(
    "**/EngineStateMachine*", "**/PlayerPhase*",
    "**/PlaybackErrorMapper*", "**/HttpStatusReader*",
    "**/EngineTuning*", "**/AspectRatioPlan*", "**/ScaleMode*",
    "**/AudioPath*", "**/AudioPathClassifier*",
    "**/DefaultEngineSelector*", "**/PlaybackEventLogger*", "**/PlaybackSnapshot*",
)

tasks.register<JacocoReport>("jacocoPureReport") {
    group = "verification"
    description = "Coverage of :core:player's pure (unit-testable) classes."
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(files("$projectDir/src/main/kotlin"))
    classDirectories.setFrom(
        files(layout.buildDirectory.dir("tmp/kotlin-classes/debug")).asFileTree.matching {
            include(pureClassPatterns)
            exclude("**/PlaybackSnapshot\$Companion*")
        },
    )
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            // AGP writes the agent output here for the jacoco task extension.
            include("jacoco/*.exec")
        },
    )
}
