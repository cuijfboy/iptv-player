package ilab.iptv.player.core.log

import ilab.iptv.player.core.common.LogEvent

/**
 * One log outlet (docs/02 §4.1, docs/03 §5; extension point E8). Implementations are registered
 * with Hilt `@IntoSet`, so adding an outlet never touches [LogBus].
 *
 * Contract:
 * - [write] is called from the single log-dispatch thread, never from the caller's thread;
 * - a sink must not throw — if it does, [LogBus] isolates the failure and keeps the other sinks
 *   running — but it must also not swallow errors silently forever (count them);
 * - [flush] blocks until this sink has written everything it buffered (docs/03 §5).
 */
interface LogSink {
    fun write(event: LogEvent)

    fun flush()

    val id: String
}
