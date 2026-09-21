plugins {
    id("iptv.android.library")
    id("iptv.room")
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    implementation(project(":core:log"))
}
