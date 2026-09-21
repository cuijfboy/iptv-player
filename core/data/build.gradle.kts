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
}
