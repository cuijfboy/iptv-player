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

    // DispatcherProvider lives in `:core:common` but the interface hands out `CoroutineDispatcher`,
    // and `:core:common` keeps coroutines `implementation` — so this module declares what it uses.
    // The `-android` artifact is what provides `Dispatchers.Main` and the Looper/Handler dispatcher
    // conversion `AndroidDispatcherProvider` needs (docs/02 §10).
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}
