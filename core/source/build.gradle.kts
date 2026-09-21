plugins {
    id("iptv.android.library")
    id("iptv.hilt")
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:log"))
    implementation(project(":core:network"))

    // Test-only: the opt-in real-network deep-probe sample drives the production OkHttp transport
    // (`OkHttpFetcher`), which `:core:network` keeps `implementation`-hidden from this module's main
    // compile classpath. Nothing in `src/main` sees okhttp (docs/02 §3.2 rule 5 is about project
    // dependencies; the engine guard only checks `project(":…")` declarations).
    testImplementation(libs.okhttp)
}

// The real-network deep-probe sample (P2-4b) is gated behind a Gradle property so `check` and CI
// stay hermetic: `./gradlew :core:source:testDebugUnitTest -Piptv.netSample=1`.
tasks.withType<Test>().configureEach {
    systemProperty("iptv.netSample", providers.gradleProperty("iptv.netSample").getOrElse(""))
}
