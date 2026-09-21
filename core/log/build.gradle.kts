plugins {
    id("iptv.android.library")
    // Hilt: the log sinks are registered as @IntoSet multibindings (docs/02 §9 extension point E8)
    // and the diagnostics activity is an @AndroidEntryPoint.
    id("iptv.hilt")
}

dependencies {
    api(project(":core:common"))

    // The log console is an @AndroidEntryPoint, so it must be a ComponentActivity.
    implementation(libs.androidx.activity)
}
