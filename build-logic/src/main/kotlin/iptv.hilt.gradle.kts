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
