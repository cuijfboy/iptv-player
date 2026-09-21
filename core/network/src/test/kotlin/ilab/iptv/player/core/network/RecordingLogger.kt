package ilab.iptv.player.core.network

import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger

/** Test double: captures the event codes a production logger would emit. */
class RecordingLogger : Logger {
    val codes = mutableListOf<String>()
    val events = mutableListOf<LogEvent>()

    override fun log(event: LogEvent) {
        codes += event.code
        events += event
    }

    override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
        codes += code
    }

    override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
        codes += code
    }

    override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
        codes += code
    }

    override fun w(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        codes += code
    }

    override fun e(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        codes += code
    }

    override fun flush(timeoutMs: Long) = Unit

    fun count(code: String): Int = codes.count { it == code }
}
