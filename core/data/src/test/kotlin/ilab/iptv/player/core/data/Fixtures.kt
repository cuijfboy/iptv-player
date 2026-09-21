package ilab.iptv.player.core.data

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Reads files by repo-relative path. Unit tests run with the module directory as their working
 * directory, so the repo root is found by walking up for the file only the root has — the same trick
 * `:core:source`'s tests use, so the 658-row fixture is read from where it actually lives.
 */
object Fixtures {

    /** The bundled P1-2 fixture, read from the module's asset folder rather than the APK. */
    const val BASELINE_PLAYLIST = "core/data/src/main/assets/playlists/p1-2-baseline.m3u"

    val root: Path by lazy { findRepoRoot() }

    fun text(relative: String): String =
        String(Files.readAllBytes(root.resolve(relative)), Charsets.UTF_8)

    private fun findRepoRoot(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        error("repo root not found above ${Paths.get("").toAbsolutePath()}")
    }
}
