plugins {
    id("iptv.android.feature")
}

dependencies {
    // The source list is a RecyclerView with a remote-driven focus path (docs/02 §8.2). Leanback
    // brings recyclerview transitively, exactly like the browse screen.
    implementation(libs.androidx.leanback)
    // `viewModelScope` for the StateFlow the screen collects (docs/02 §8.1).
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
