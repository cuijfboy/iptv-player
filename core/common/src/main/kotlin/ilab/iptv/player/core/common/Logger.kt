package ilab.iptv.player.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * Event model + logging facade (docs/02 §4.1). docs/03 §3–§4 is the single source of truth for
 * these types; the `EventCodes` constants land with the logger skeleton (P0-2).
 */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR, FATAL }

enum class LogCategory { APP, UI, NET, SOURCE, VALIDATE, EPG, DB, PLAYER, SERVICE, WORK, PERF }

data class LogEvent(
    val seq: Long,                    // monotonic, for ordering / dedup
    val ts: Long,                     // wall clock ms
    val elapsedMs: Long,              // since process start — locates stalls
    val level: LogLevel,
    val category: LogCategory,
    val code: String,                 // event code, docs/03 §3.3
    val message: String,              // human readable, already redacted
    val fields: Map<String, Any?> = emptyMap(),
    val error: Throwable? = null,
    val thread: String,
    val screen: String? = null,
    val sessionId: String,            // correlates one refresh / one playback
)

interface Logger {
    fun log(event: LogEvent)

    fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?> = emptyMap()) =
        log(LogEvent(0, 0, 0, LogLevel.VERBOSE, category, code, message, fields, null, "", null, ""))

    fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?> = emptyMap()) =
        log(LogEvent(0, 0, 0, LogLevel.DEBUG, category, code, message, fields, null, "", null, ""))

    fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?> = emptyMap()) =
        log(LogEvent(0, 0, 0, LogLevel.INFO, category, code, message, fields, null, "", null, ""))

    fun w(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) = log(LogEvent(0, 0, 0, LogLevel.WARN, category, code, message, fields, error, "", null, ""))

    fun e(
        category: LogCategory,
        code: String,
        message: String,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) = log(LogEvent(0, 0, 0, LogLevel.ERROR, category, code, message, fields, error, "", null, ""))

    fun flush(timeoutMs: Long = 500)
}

/** Session ids correlate a refresh / playback run, e.g. "play-7f3a". */
interface SessionIdFactory {
    fun newId(prefix: String): String
}

/** Strips tokens/credentials from URLs before logging or export (docs/03 §11). */
interface Redactor {
    fun redact(url: String): String
}

/** Injected so tests control time; never call System.currentTimeMillis directly. */
interface Clock {
    fun nowMs(): Long
}

interface DispatcherProvider {
    val io: CoroutineDispatcher
    val default: CoroutineDispatcher
    val main: CoroutineDispatcher

    /** Single parallelism: the one thread the player engine may run on (docs/02 §4.5 C2). */
    val engine: CoroutineDispatcher

    /** Single thread: state machines, serialized writes. */
    val single: CoroutineDispatcher
}

/** Application-wide scope. GlobalScope is banned (docs/02 §4.1). */
interface AppScopeProvider {
    val appScope: CoroutineScope
}
