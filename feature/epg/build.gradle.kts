plugins {
    id("iptv.android.feature")
}

dependencies {
    // `viewModelScope` + `StateFlow` for the grid's one-way data flow (docs/02 §8.1).
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // The window query is re-issued off the main thread whenever the viewport crosses a page boundary.
    implementation(libs.kotlinx.coroutines.android)

    // The view-model tests drive `viewModelScope` on a test dispatcher (BUG-20260922-018's grid/panel
    // parity assertion), the same pair `:feature:wizard` declares for the same reason.
    testImplementation(libs.kotlinx.coroutines.test)
}
