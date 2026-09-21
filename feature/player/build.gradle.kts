plugins {
    id("iptv.android.feature")
}

dependencies {
    // Guard rule 4 (docs/02 §3.2): :feature:player is the only feature allowed to see :core:player.
    implementation(project(":core:player"))
    // `AspectRatioFrameLayout` is the view half of the §7.4 four-mode display switch: the engine
    // carries `AspectRatioMode`, this view is what actually letterboxes/crops/4:3s the surface.
    implementation(libs.media3.ui)
    // `viewModelScope` for the PlayerViewModel (docs/02 §8.1 one-way data flow).
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // The session and the fade timer both hop between the engine dispatcher and the main thread.
    implementation(libs.kotlinx.coroutines.android)
    // The browse screen starts this Activity through PlayerContract (:core:ui), and both features
    // share the contract's intent filter — no feature-to-feature dependency (docs/02 §3.2).
    testImplementation(libs.truth)
}
