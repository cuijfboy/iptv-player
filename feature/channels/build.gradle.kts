plugins {
    id("iptv.android.feature")
}

dependencies {
    // Leanback pulls androidx.recyclerview (transitively, through its POM) — the virtualized list the
    // docs/02 §8.2 freeze requires for 658+ channels. No image-loading library yet: P1-2 only
    // promises a logo *placeholder*, so a generated drawable + the channel's initial is enough.
    implementation(libs.androidx.leanback)
    // The list builds 658 rows and parses a playlist off the main thread (docs/02 §4.5 C6).
    implementation(libs.kotlinx.coroutines.android)
    // `viewModelScope` for the StateFlow the browse screen collects (docs/02 §8.1).
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
}
