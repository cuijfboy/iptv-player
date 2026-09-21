import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention plugin for **pure Kotlin** modules (`:core:common`, `:core:model`, `:core:domain`).
 *
 * These are `java-library` modules: no Android plugin, no `android.*` / `androidx.*` imports.
 * The rule is enforced at build time by the `verifyPureKotlin` task (see docs/02 §3.2 rule 1);
 * it is wired into `check`, so `./gradlew check` and CI fail on any Android leak.
 */
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    add("implementation", catalog.findLibrary("kotlinx-coroutines-core").get())
    add("testImplementation", catalog.findLibrary("junit").get())
    add("testImplementation", catalog.findLibrary("truth").get())
    add("testImplementation", catalog.findLibrary("turbine").get())
}

// Captured in project scope: inside the task action `path` is the TASK path, not the module path.
val guardedModulePath = path

val verifyPureKotlin = tasks.register("verifyPureKotlin") {
    group = "verification"
    description = "Fails if this pure-Kotlin module imports android.* or androidx.* (docs/02 §3.2)."
    val guardedModule = guardedModulePath
    val srcRoot = layout.projectDirectory.dir("src")
    val baseDir = projectDir
    val sources = fileTree(srcRoot) { include("**/*.kt", "**/*.java", "**/*.kts") }
    inputs.files(sources)
    doLast {
        val offenders = mutableListOf<String>()
        sources.forEach { file ->
            file.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.startsWith("import ")) {
                    val imported = trimmed.removePrefix("import ").trim()
                    if (imported.startsWith("android.") || imported.startsWith("androidx.")) {
                        offenders += "  ${file.relativeTo(baseDir)}  ->  $imported"
                    }
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Purity guard failed: $guardedModule is a pure-Kotlin module but imports Android:\n" +
                    offenders.joinToString("\n")
            )
        }
        logger.lifecycle("verifyPureKotlin: OK ($guardedModule)")
    }
}

tasks.named("check") { dependsOn(verifyPureKotlin) }
