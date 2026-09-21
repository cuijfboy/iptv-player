plugins {
    id("iptv.android.library")
    id("iptv.hilt")
}

android {
    testOptions {
        // The Room tests drive the real seeder against the bundled fixture, so the unit tests need the
        // merged assets (`core/data/src/main/assets/...`) on Robolectric's AssetManager.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:log"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:source"))
    implementation(project(":core:epg"))

    // In-memory repository: Coroutine Flow for observe() and a Mutex around the one-time catalog
    // load (P1-2 has no Room yet, so this is state, not I/O).
    implementation(libs.kotlinx.coroutines.core)

    // P2-1: the Room-backed repositories live here, so this module needs Room's runtime API
    // (`RoomDatabase`, `withTransaction`) on its compile classpath. `:core:database` keeps it
    // `implementation`, which is why it must be declared again instead of leaking through.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // Test-only: the opt-in real-network EPG sample (P2-7) drives the production OkHttp transport
    // through `OkHttpFetcher`, which `:core:network` keeps `implementation`-hidden from this module's
    // main compile classpath. Nothing in `src/main` sees okhttp (docs/02 §3.2 rule 5 is about project
    // dependencies; the guard only checks `project(":…")` declarations). Same shape as
    // `:core:source`'s deep-probe sample.
    testImplementation(libs.okhttp)

    // P2-1 test path: the Room repositories and the seeder are driven through an in-memory database
    // on the JVM (Robolectric), so `./gradlew check` covers them without a device (docs/02 §13).
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The real-network EPG sample is gated so `check` and CI stay hermetic:
// `./gradlew :core:data:testDebugUnitTest -Piptv.epgSample=1`.
tasks.withType<Test>().configureEach {
    systemProperty("iptv.epgSample", providers.gradleProperty("iptv.epgSample").getOrElse(""))
}
