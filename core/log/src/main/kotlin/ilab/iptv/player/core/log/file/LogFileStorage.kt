package ilab.iptv.player.core.log.file

import android.content.Context
import java.io.File

/**
 * Where the on-disk log lives: the app's **own external files directory**
 * (`/sdcard/Android/data/<pkg>/files/logs/`, docs/03 §6) — no storage permission, and reachable by
 * `adb pull` / `adb shell ls` on the shell user.
 *
 * The `/sdcard` form is derived from the package name instead of `Environment`, so the pull command
 * shown in the console is exactly the path the docs use.
 */
object LogFileStorage {

    const val LOG_DIR_NAME = "logs"

    /** Absolute directory, or `null` when the device has no external files directory at all. */
    fun logDirectory(context: Context): String? =
        context.getExternalFilesDir(null)?.let { File(it, LOG_DIR_NAME).absolutePath }

    /** `/sdcard/Android/data/<pkg>/files/logs` — the published, adb-friendly form (docs/03 §6). */
    fun deviceDirectory(context: Context): String =
        "/sdcard/Android/data/${context.packageName}/files/$LOG_DIR_NAME"

    /** The one-liner a tester can paste; `null` when there is nothing to pull. */
    fun adbPullCommand(context: Context): String? =
        logDirectory(context)?.let { "adb pull ${deviceDirectory(context)} ./tv-logs" }
}
