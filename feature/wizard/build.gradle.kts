plugins {
    id("iptv.android.feature")
}

dependencies {
    // `viewModelScope` + `by viewModels()` for the StateFlow the screen collects (docs/02 §8.1).
    // The same pair `:feature:settings` declares; the three panels are plain views, so this module
    // needs no RecyclerView and no leanback.
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // The wizard drives the source/import ports and observes the manual refresh job off the main
    // thread (docs/02 §4.5 C6).
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
