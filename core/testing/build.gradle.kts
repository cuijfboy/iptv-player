plugins {
    id("iptv.android.library")
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(libs.junit)
    api(libs.truth)
    api(libs.turbine)
}
