plugins {
    id("iptv.android.library")
    id("iptv.hilt")
}

dependencies {
    api(project(":core:common"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
}
