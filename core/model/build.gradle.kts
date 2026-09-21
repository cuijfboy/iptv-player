// Pure Kotlin (JVM) — no Android dependency, enforced by verifyPureKotlin (docs/02 §3.2).
plugins {
    id("iptv.kotlin.library")
}

dependencies {
    // The docs/02 §3.2 matrix has always allowed `model → common`; it is used from P1-2 on, because
    // §4.2's `StreamOutcome` carries a `FailureClass`, whose single definition is §4.1 in :core:common.
    api(project(":core:common"))
}
