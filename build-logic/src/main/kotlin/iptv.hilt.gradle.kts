import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin: Hilt (KSP) dependency injection (docs/02 §14).
 *
 * Apply **after** the Android/Kotlin plugin for the module. Extension points are registered
 * through `@IntoSet` multibindings (docs/02 §5), never through service lookup.
 */
plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("implementation", catalog.findLibrary("hilt-android").get())
    add("ksp", catalog.findLibrary("hilt-compiler").get())
}

/**
 * CI-FLAKE-1: keep lint off the Hilt/KSP generated sources while they are being (re)generated.
 *
 * AGP wires each `lintAnalyze<Variant>` task to `pre<Variant>Build` and nothing else. The **test**
 * variants (`lintAnalyze<Variant>UnitTest` / `...AndroidTest`) therefore end up with no ordering at
 * all against the Hilt/KSP generators of the variant under test, even though those generators
 * *replace* directories lint reads — `build/generated/hilt/component_sources/<variant>` and
 * `build/intermediates/hilt/component_classes/<variant>` (Hilt's aggregating javac task rewrites the
 * `Dagger<App>_HiltComponents_*.java` every run) plus `build/generated/ksp/<variant>/java`. Under
 * parallel execution lint can read one of those `.java` files while the generator is deleting and
 * rewriting it, which surfaces as `Unexpected failure during lint analysis … (No such file or
 * directory)` and goes green on a plain re-run because the second attempt finds the files stable.
 *
 * Declare the order explicitly, for both the analysed variant and (for the test variants) the variant
 * under test. This only adds task ordering — it changes no lint rule and masks no real failure: a
 * genuinely red lint still fails, just after the sources are settled.
 */
tasks.matching { it.name.startsWith("lintAnalyze") }.configureEach {
    // "lintAnalyzeDebugUnitTest" -> "DebugUnitTest"; "lintAnalyzeDebug" -> "Debug"; the suffix's
    // capitalisation matches the AGP generator names below.
    val analysed = name.removePrefix("lintAnalyze")
    val underTest = analysed.removeSuffix("UnitTest").removeSuffix("AndroidTest")
    listOf(analysed, underTest).distinct().forEach { variant ->
        listOf("ksp${variant}Kotlin", "hiltAggregateDeps$variant", "hiltJavaCompile$variant").forEach { generator ->
            tasks.findByName(generator)?.let { dependsOn(it) }
        }
    }
}
