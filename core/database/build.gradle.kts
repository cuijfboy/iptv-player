plugins {
    id("iptv.android.library")
    id("iptv.room")
}

android {
    testOptions {
        // Robolectric (JVM) tests are the CI path for Room: docs/02 §13 does not want DAO tests to
        // need a device, and `MigrationTestHelper` reads the exported schema JSON out of the *test*
        // assets. `schemas/` is the KSP output directory (see `iptv.room`), so wiring it in as a test
        // asset root makes `ilab.iptv.player.core.database.IptvDatabase/1.json` resolvable — the
        // directory is the database class's canonical name, which is what the helper looks for.
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets.getByName("test") {
        assets.srcDir(layout.projectDirectory.dir("schemas"))
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":core:log"))

    // JVM (Robolectric) test path: no device, so `./gradlew check` and CI run the DAO/index/migration
    // tests. Instrumented equivalents are deliberately NOT on the CI-required path (docs/02 §13).
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}
