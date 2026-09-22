package ilab.iptv.player.feature.settings.diag

import android.content.Context
import java.io.File

/**
 * Where the export package lands, and the two ways to get it off the TV (docs/03 §8).
 *
 * Same root and same reasoning as the log directory (`core:log`'s `LogFileStorage`): the app's **own**
 * external files directory needs no storage permission, is reachable with `adb pull`, and — unlike
 * `/data/data/…` — can also be reached from a file manager on the TV itself. Kept next to the
 * exporter instead of in `:core:log` because the log module is about logging, not about diagnostics
 * packages.
 */
object DiagStorage {

    const val EXPORT_DIR_NAME = "export"

    fun exportDirectory(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, EXPORT_DIR_NAME) }

    /** `/sdcard/Android/data/<pkg>/files/export` — the published, adb-friendly spelling. */
    @Suppress("SdCardPath")
    fun deviceDirectory(context: Context): String =
        "/sdcard/Android/data/${context.packageName}/files/$EXPORT_DIR_NAME"

    fun adbPullCommand(context: Context): String? =
        exportDirectory(context)?.let { "adb pull ${deviceDirectory(context)} ./tv-diag" }

    /** The "如何取出" block docs/03 §8 asks the panel to show after a successful export. */
    fun extractionLines(context: Context): List<String> = buildList {
        add("adb pull ${deviceDirectory(context)} ./tv-diag")
        add("电视上直接看：内部存储/Android/data/${context.packageName}/files/$EXPORT_DIR_NAME")
    }
}
