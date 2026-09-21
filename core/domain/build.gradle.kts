import org.gradle.testing.jacoco.tasks.JacocoReport

// Pure Kotlin (JVM) — ports and policies only, no Android dependency (docs/02 §3.2).
plugins {
    id("iptv.kotlin.library")
    jacoco
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
}

// P1-6's acceptance item is "单测覆盖 ≥80%", so this module — and only this one — carries JaCoCo.
// The report is scoped to the P1-6 package, so the percentage means "coverage of the fail-over
// policy", not "coverage of :core:domain" (the channel/* code from P1-2 is out of scope here).
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named("test"))
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) { include("ilab/iptv/player/core/domain/playback/**") }
            },
        ),
    )
    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }
}
