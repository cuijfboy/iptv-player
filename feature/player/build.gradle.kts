plugins {
    id("iptv.android.feature")
}

dependencies {
    // Guard rule 4 (docs/02 §3.2): :feature:player is the only feature allowed to see :core:player.
    implementation(project(":core:player"))
    // `AspectRatioFrameLayout` is the view half of the §7.4 four-mode display switch: the engine
    // carries `AspectRatioMode`, this view is what actually letterboxes/crops/4:3s the surface.
    implementation(libs.media3.ui)
    // P1-7: `MediaSession` + `MediaSessionService` for the foreground playback service — the remote's
    // play/pause key and the TV's system media control act on the engine through this session.
    implementation(libs.media3.session)
    // `viewModelScope` for the PlayerViewModel (docs/02 §8.1 one-way data flow).
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // The session and the fade timer both hop between the engine dispatcher and the main thread.
    implementation(libs.kotlinx.coroutines.android)
    // The browse screen starts this Activity through PlayerContract (:core:ui), and both features
    // share the contract's intent filter — no feature-to-feature dependency (docs/02 §3.2).
    testImplementation(libs.truth)
    // P1-5's fail-over wiring is a coroutine loop with a watchdog on a virtual clock, so the tests
    // drive virtual time instead of sleeping through real 1 s backoffs and 8 s stall thresholds.
    testImplementation(libs.kotlinx.coroutines.test)
}
