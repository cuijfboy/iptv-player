package ilab.iptv.player.core.log

import android.util.Log
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel

/**
 * Writes to logcat with tag `IPTV/<category>` (docs/03 §5), so a developer can narrow the stream
 * by tag on the command line (docs/03 §6) without any filter inside the app.
 */
class LogcatSink(
    override val id: String = ID,
    private val tagPrefix: String = TAG_PREFIX,
) : LogSink {

    override fun write(event: LogEvent) {
        val tag = "$tagPrefix/${event.category.name}"
        val priority = event.level.logcatPriority()
        Log.println(priority, tag, format(event))
        event.error?.let { Log.println(priority, tag, Log.getStackTraceString(it)) }
    }

    /** logcat has no user-space buffer of ours to drain. */
    override fun flush() = Unit

    private fun format(event: LogEvent): String = buildString {
        append("seq=").append(event.seq)
        append(" t=").append(event.ts)
        append(" +").append(event.elapsedMs).append("ms")
        append(' ').append(event.code)
        if (event.message.isNotBlank()) append(' ').append(event.message)
        if (event.fields.isNotEmpty()) append(' ').append(event.fields)
        append(" session=").append(event.sessionId)
        append(" thread=").append(event.thread)
    }

    companion object {
        const val ID = "logcat"
        const val TAG_PREFIX = "IPTV"
    }
}

/** logcat has no FATAL priority; FATAL maps to ASSERT (docs/03 §3.1: FATAL == crash). */
internal fun LogLevel.logcatPriority(): Int = when (this) {
    LogLevel.VERBOSE -> Log.VERBOSE
    LogLevel.DEBUG -> Log.DEBUG
    LogLevel.INFO -> Log.INFO
    LogLevel.WARN -> Log.WARN
    LogLevel.ERROR -> Log.ERROR
    LogLevel.FATAL -> Log.ASSERT
}
