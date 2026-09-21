package ilab.iptv.player.core.source

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Reads the sample files in `tools/fixtures/` (docs/04 P0-8). Unit tests run with the module
 * directory as their working directory, so the repo root is found by walking upwards for the file
 * that only the root has. Keeping the samples in `tools/fixtures/` instead of `src/test/resources`
 * means one copy, next to the documentation that describes them.
 */
object Fixtures {

    val root: Path by lazy { findRepoRoot() }

    fun path(relative: String): Path = root.resolve("tools/fixtures").resolve(relative)

    fun bytes(relative: String): ByteArray = Files.readAllBytes(path(relative))

    fun text(relative: String): String = String(bytes(relative), Charsets.UTF_8)

    private fun findRepoRoot(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts")) && Files.isDirectory(dir.resolve("tools"))) {
                return dir
            }
            dir = dir.parent
        }
        error("repo root not found above ${Paths.get("").toAbsolutePath()}")
    }
}
