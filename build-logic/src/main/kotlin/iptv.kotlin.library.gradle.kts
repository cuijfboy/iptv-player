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
    description = "Fails if this pure-Kotlin module references android.* or androidx.* (docs/02 §3.2)."
    val guardedModule = guardedModulePath
    val srcRoot = layout.projectDirectory.dir("src")
    val baseDir = projectDir
    val sources = fileTree(srcRoot) { include("**/*.kt", "**/*.java", "**/*.kts") }
    inputs.files(sources)
    doLast {
        val offenders = mutableListOf<String>()
        // Two nets (BUG-005): (a) Android imports, and (b) a reference with no import line at all,
        // e.g. `android.os.Bundle::class.java`. The second net is deliberately narrow — it wants a
        // bare `android.` / `androidx.` + identifier, so `com.example.android.Foo` (a longer package
        // path) does not match — and it only looks at code, never comments or string literals.
        val importLine = Regex("""^import\s+(android|androidx)\.""")
        val androidReference = Regex("""(?<![\w.])androidx?\.[A-Za-z_]""")
        sources.forEach { file ->
            var lineNumber = 0
            var inBlockComment = false
            var inRawString = false
            file.forEachLine { rawLine ->
                lineNumber++
                // Drop comments and string/char literals so prose and test data never trip the guard.
                val code = StringBuilder()
                var i = 0
                while (i < rawLine.length) {
                    val current = rawLine[i]
                    val next = if (i + 1 < rawLine.length) rawLine[i + 1] else ' '
                    when {
                        inBlockComment -> {
                            if (current == '*' && next == '/') {
                                inBlockComment = false
                                i += 2
                            } else {
                                i++
                            }
                        }
                        inRawString -> {
                            if (rawLine.startsWith("\"\"\"", i)) {
                                inRawString = false
                                i += 3
                            } else {
                                i++
                            }
                        }
                        current == '/' && next == '/' -> i = rawLine.length
                        current == '/' && next == '*' -> {
                            inBlockComment = true
                            i += 2
                        }
                        rawLine.startsWith("\"\"\"", i) -> {
                            inRawString = true
                            i += 3
                        }
                        current == '"' || current == '\'' -> {
                            val quote = current
                            i++
                            while (i < rawLine.length) {
                                when {
                                    rawLine[i] == '\\' -> i += 2
                                    rawLine[i] == quote -> { i++; break }
                                    else -> i++
                                }
                            }
                            code.append(' ')
                        }
                        else -> {
                            code.append(current)
                            i++
                        }
                    }
                }

                val text = code.toString()
                val trimmed = text.trim()
                val offense = when {
                    importLine.containsMatchIn(trimmed) -> trimmed
                    androidReference.containsMatchIn(text) -> "Android reference: $trimmed"
                    else -> null
                }
                if (offense != null) {
                    offenders += "  ${file.relativeTo(baseDir)}:$lineNumber  ->  $offense"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Purity guard failed: $guardedModule is a pure-Kotlin module but references Android:\n" +
                    offenders.joinToString("\n")
            )
        }
        logger.lifecycle("verifyPureKotlin: OK ($guardedModule)")
    }
}

tasks.named("check") { dependsOn(verifyPureKotlin) }
