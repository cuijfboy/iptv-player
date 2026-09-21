plugins {
    id("iptv.android.library")
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:domain"))
    api(project(":core:design"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
}
