package ilab.iptv.spike

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Every spike run writes one JSON artefact into the app's external files dir and mirrors a compact
 * line into logcat, so a result survives either channel being lost.
 */
object SpikeIo {

    const val TAG = "SPIKE"

    fun write(context: Context, name: String, text: String) {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val file = File(dir, name)
        file.writeText(text)
        Log.i(TAG, "WROTE ${file.absolutePath} bytes=${text.length}")
    }
}
