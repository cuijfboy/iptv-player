// Pure Kotlin (JVM) — ports and policies only, no Android dependency (docs/02 §3.2).
plugins {
    id("iptv.kotlin.library")
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
}
