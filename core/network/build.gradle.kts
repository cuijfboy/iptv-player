plugins {
    id("iptv.android.library")
}

dependencies {
    api(project(":core:common"))
    implementation(libs.okhttp)
}
