plugins {
    id("iptv.android.library")
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    api(project(":core:log"))
    implementation(project(":core:network"))
}
