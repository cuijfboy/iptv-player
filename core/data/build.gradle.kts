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

    // P2-1 test path: the Room repositories and the seeder are driven through an in-memory database
    // on the JVM (Robolectric), so `./gradlew check` covers them without a device (docs/02 §13).
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
