import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin for `:feature:*` presentation modules (docs/02 §3.1 / §3.2).
 *
 * A feature may only depend on what the matrix allows: common, log, model, domain, ui, design.
 * It must NOT touch `:core:database`, `:core:network`, `:core:source` or `:core:epg` (guard rule 2).
 * `:core:player` is the ONE exception and only for `:feature:player` (guard rule 4), so it is
 * declared in that module, not here.
 */
plugins {
    id("iptv.android.library")
    id("iptv.hilt")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("implementation", project(":core:common"))
    add("implementation", project(":core:log"))
    add("implementation", project(":core:model"))
    add("implementation", project(":core:domain"))
    add("implementation", project(":core:ui"))
    add("implementation", project(":core:design"))
    add("implementation", catalog.findLibrary("androidx-core-ktx").get())
    add("implementation", catalog.findLibrary("androidx-activity").get())
}
