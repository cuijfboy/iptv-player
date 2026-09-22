plugins {
    id("iptv.android.feature")
}

dependencies {
    // Leanback pulls androidx.recyclerview (transitively, through its POM) — the virtualized list the
    // docs/02 §8.2 freeze requires for 658+ channels.
    implementation(libs.androidx.leanback)
    // P2-3 (docs/04): channel logos. Coil loads asynchronously off the main thread, keeps its own
    // disk cache, and cancels a request when the row is recycled — the three things "no network does
    // not crash and scrolling does not block" needs. The placeholder stays a generated drawable, so a
    // missing logo costs nothing.
    implementation(libs.coil)
    // The list builds 658 rows and parses a playlist off the main thread (docs/02 §4.5 C6).
    implementation(libs.kotlinx.coroutines.android)
    // `viewModelScope` for the StateFlow the browse screen collects (docs/02 §8.1).
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
