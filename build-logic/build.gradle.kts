import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    `kotlin-dsl`
}

// The generated `libs` accessors only expose the typed aliases (`libs.android.gradle.plugin`), not
// `findLibrary(...)`, so resolve the shared catalog object explicitly. build-logic/settings.gradle.kts
// points this catalog at the root `gradle/libs.versions.toml`, so versions stay single-sourced.
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // Gradle plugin classpath for the convention plugins below.
    implementation(catalog.findLibrary("android-gradle-plugin").get())
    implementation(catalog.findLibrary("kotlin-gradle-plugin").get())
    implementation(catalog.findLibrary("ksp-gradle-plugin").get())
    implementation(catalog.findLibrary("hilt-gradle-plugin").get())
}
