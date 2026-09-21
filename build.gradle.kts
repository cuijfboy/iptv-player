// Root build. Nothing is applied here; every module picks its own convention plugin.
// Plugin versions are declared once so subprojects can apply them by id.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false

    // Build-time dependency guard for docs/02 §3.2 rules 2–5 (CodeReview CR-01). Applied here
    // because it needs the whole module graph; every module's `check` depends on its task, so
    // `./gradlew check` fails on a violation.
    id("iptv.module.dependencies")
}
