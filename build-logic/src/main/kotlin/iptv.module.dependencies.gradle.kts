import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * Build-time guard for the docs/02 §3.2 dependency rules 2–5 (rule 1 — "pure Kotlin modules must
 * not touch Android" — lives in `iptv.kotlin.library`; rules 2–5 had NO build-time enforcement, see
 * CodeReview CR-01).
 *
 * **Scope (god's ruling, 2026-09-21).** Only a module's OWN declaration counts: the
 * `implementation(...)` / `api(...)` entries in its `build.gradle.kts` or in the convention plugin
 * it applies. A dependency that merely travels transitively is NOT a violation. The counterpart is
 * that an intermediate module must expose a restricted module with `implementation`, never `api`,
 * so a restricted module can never reach a consumer's compile classpath.
 *
 * Rules enforced:
 *  1b. a pure-Kotlin module (`:core:common` / `:core:model` / `:core:domain`) must not declare an
 *      Android module either — `verifyPureKotlin` guards its SOURCE, this guards its DEPENDENCIES.
 *  2. `:feature:*` (other than `:feature:player`) must not declare `:core:{database,network,source,epg}`.
 *  3. `:app` must not declare `:core:{database,network,source,epg,player}` — it only assembles.
 *  4. `:core:player` may only be declared by `:feature:player`.
 *  5. no module may expose a restricted module through `api` / `compileOnlyApi`; no dependency cycles.
 *
 * The guard is applied ONCE, to the root project (it needs the whole module graph for cycles), and
 * `check` in every module depends on its task, so `./gradlew check` fails on a violation.
 * Run it on its own with `./gradlew verifyModuleDependencies`.
 */

require(project == project.rootProject) {
    "iptv.module.dependencies is applied to the root project only: it validates the whole module graph."
}

val guardedRoot = project

/** Modules that must never leak past their intended consumer (docs/02 §3.2 rules 3–5). */
val restrictedModules = listOf(":core:database", ":core:network", ":core:source", ":core:epg", ":core:player")

/** Rule 2 + rule 4: what a plain feature must not declare (rule 4 puts `:core:player` off every feature but `:feature:player`). */
val featureForbiddenModules = listOf(":core:database", ":core:network", ":core:source", ":core:epg", ":core:player")

/** Rule 3: what `:app` must not declare directly. */
val appForbiddenModules = listOf(":core:database", ":core:network", ":core:source", ":core:epg", ":core:player")

val playerModule = ":core:player"
val playerFeature = ":feature:player"

/**
 * Declared (not resolved) project dependencies of one module, grouped by configuration name.
 *
 * Only "declaration buckets" are read — configurations that are neither resolvable nor consumable,
 * i.e. `implementation`, `api`, `compileOnly`, `debugImplementation`, … AGP's own classpath
 * configurations (`debugUnitTestCompileClasspath`, `releaseAndroidTestRuntimeClasspath`, …) are
 * resolvable, add a project dependency on the module ITSELF and carry the full transitive set, so
 * reading them would report phantom self-cycles and phantom transitive edges.
 */
fun declaredProjectDependencies(target: Project): Map<String, Set<String>> =
    target.configurations
        .filter { !it.isCanBeResolved && !it.isCanBeConsumed }
        .mapNotNull { configuration ->
            val paths = configuration.dependencies
                .filterIsInstance<ProjectDependency>()
                .map { it.path }
                .toSortedSet()
            if (paths.isEmpty()) null else configuration.name to paths
        }
        .toMap()

/** First dependency cycle found in the declared-dependency graph, or null. */
fun findDependencyCycle(graph: Map<String, Set<String>>): List<String>? {
    val settled = mutableSetOf<String>()
    val stack = mutableListOf<String>()
    val onStack = mutableSetOf<String>()
    var found: List<String>? = null

    fun visit(node: String) {
        if (found != null || node in settled) return
        settled += node
        stack += node
        onStack += node
        for (next in graph[node].orEmpty().sorted()) {
            if (next !in graph) continue
            if (next in onStack) {
                found = stack.drop(stack.indexOf(next)) + next
                return
            }
            visit(next)
            if (found != null) return
        }
        onStack -= node
        stack.removeAt(stack.size - 1)
    }

    graph.keys.sorted().forEach { visit(it) }
    return found
}

val verifyModuleDependencies = tasks.register("verifyModuleDependencies") {
    group = "verification"
    description = "docs/02 §3.2 rules 2–5: forbidden direct project dependencies, api exposure, module cycles."
    // The graph is read at execution time, so the task always runs (no outputs to track).
    outputs.upToDateWhen { false }
    doLast {
        // `:core` and `:feature` are container projects with no build script of their own.
        val modules = guardedRoot.subprojects.filter { it.buildFile.exists() }.sortedBy { it.path }
        val declared = modules.associate { it.path to declaredProjectDependencies(it) }
        val androidModules = modules
            .filter { it.plugins.hasPlugin("com.android.library") || it.plugins.hasPlugin("com.android.application") }
            .map { it.path }
            .toSet()
        val problems = mutableListOf<String>()

        modules.forEach { module ->
            val byConfiguration = declared.getValue(module.path)
            val declaredPaths = byConfiguration.values.flatten().toSortedSet()
            if (module.path !in androidModules) {
                declaredPaths.filter { it in androidModules }.forEach { offender ->
                    problems += "rule 1b: ${module.path} is a pure-Kotlin module but declares the " +
                        "Android module $offender directly"
                }
            }
            val forbidden = when {
                module.path == ":app" -> appForbiddenModules
                module.path == playerFeature -> listOf(":core:database", ":core:network", ":core:source", ":core:epg")
                module.path.startsWith(":feature:") -> featureForbiddenModules
                module.path == playerModule -> emptyList()
                else -> listOf(playerModule) // rule 4: only :feature:player may see :core:player
            }
            forbidden.filter { it in declaredPaths }.forEach { offender ->
                val rule = when {
                    module.path == ":app" -> "rule 3"
                    module.path.startsWith(":feature:") && offender != playerModule -> "rule 2"
                    else -> "rule 4"
                }
                problems += "$rule: ${module.path} declares $offender directly (see docs/02 §3.2)"
            }
            byConfiguration
                .filterKeys { it == "api" || it.endsWith("Api") }
                .forEach { (configurationName, paths) ->
                    paths.filter { it in restrictedModules }.forEach { offender ->
                        problems += "rule 5 (api exposure): ${module.path} exposes $offender through " +
                            "`$configurationName` — declare it with implementation() so it stays off " +
                            "the compile classpath of consumers"
                    }
                }
        }

        val dependencyGraph = declared.mapValues { (_, byConfiguration) ->
            byConfiguration.values.flatten().toSortedSet()
        }
        findDependencyCycle(dependencyGraph)?.let { cycle ->
            problems += "rule 5 (no cycles): ${cycle.joinToString(" -> ")}"
        }

        if (problems.isEmpty()) {
            logger.lifecycle(
                "verifyModuleDependencies: OK (${modules.size} modules; no Android dependency from a " +
                    "pure-Kotlin module, no forbidden direct dependency, no api exposure of " +
                    "$restrictedModules, no cycles)"
            )
        } else {
            throw GradleException(
                "Dependency guard failed (docs/02 §3.2):\n" + problems.joinToString("\n") { "  - $it" }
            )
        }
    }
}

// Every module's `check` runs the guard, so `./gradlew check` (and CI) can no longer miss rules 2–5.
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(verifyModuleDependencies) }
}
