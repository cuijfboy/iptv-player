import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin: Room + KSP (docs/02 §14). Apply after the Android/Kotlin plugin.
 * `exportSchema = true` + the `schemas/` output below means the schema JSON is committed (docs/02 §14).
 */
plugins {
    id("com.google.devtools.ksp")
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

configure<com.google.devtools.ksp.gradle.KspExtension> {
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.absolutePath)
    arg("room.generateKotlin", "true")
}

dependencies {
    add("implementation", catalog.findLibrary("room-runtime").get())
    add("implementation", catalog.findLibrary("room-ktx").get())
    add("ksp", catalog.findLibrary("room-compiler").get())
}
