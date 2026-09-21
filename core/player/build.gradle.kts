plugins {
    id("iptv.android.library")
    id("iptv.hilt")
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:log"))
    implementation(libs.media3.exoplayer)
}
