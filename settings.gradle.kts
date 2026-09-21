pluginManagement {
    // Convention plugins live in the `build-logic` included build (docs/02 §14).
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "iptv-player"

// --- Application / assembly ---
include(":app")

// --- core/* : infrastructure + platform + data (docs/02 §3.1) ---
include(
    ":core:common",
    ":core:model",
    ":core:domain",
    ":core:log",
    ":core:database",
    ":core:network",
    ":core:source",
    ":core:epg",
    ":core:player",
    ":core:data",
    ":core:ui",
    ":core:design",
    ":core:testing",
)

// --- feature/* : presentation (docs/02 §3.1) ---
include(
    ":feature:channels",
    ":feature:player",
    ":feature:epg",
    ":feature:settings",
    ":feature:wizard",
)
