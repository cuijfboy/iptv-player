plugins {
    id("iptv.android.feature")
}

dependencies {
    // Guard rule 4 (docs/02 §3.2): :feature:player is the only feature allowed to see :core:player.
    implementation(project(":core:player"))
    implementation(libs.media3.ui)
}
