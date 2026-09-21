plugins {
    id("iptv.android.library")
    id("iptv.hilt")
}

dependencies {
    api(project(":core:domain"))
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:log"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:source"))
    implementation(project(":core:epg"))

    // In-memory repository: Coroutine Flow for observe() and a Mutex around the one-time catalog
    // load (P1-2 has no Room yet, so this is state, not I/O).
    implementation(libs.kotlinx.coroutines.core)
}
