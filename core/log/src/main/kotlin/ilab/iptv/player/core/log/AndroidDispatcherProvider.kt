package ilab.iptv.player.core.log

import android.os.Handler
import android.os.HandlerThread
import ilab.iptv.player.core.common.DispatcherProvider
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.android.asCoroutineDispatcher as handlerAsDispatcher
import kotlinx.coroutines.asCoroutineDispatcher as executorAsDispatcher

/**
 * The process' one implementation of the frozen [`DispatcherProvider`] interface (docs/02 §4.1) —
 * `docs/02 §10`: "所有 `Dispatcher` 通过 `DispatcherProvider` 注入（可测）".
 *
 * Until now the interface existed with **no** binding: `P2-4a` and `P1-8` both recorded the TODO and
 * every caller hard-coded `Dispatchers.IO` / `Dispatchers.Default`, which is untestable (a unit test
 * cannot see whether the work really left the caller's thread) and un-swappable. This class is the
 * default the Hilt graph binds; tests bind their own.
 *
 * Threading per member:
 * - [io] / [default] / [main] are the kotlinx-dispatchers the docs table in §10 names for network,
 *   parsing and UI. They are shared process-wide, which is exactly what those pools are for.
 * - [single] is a **dedicated** single thread ("iptv-single", daemon). It is not `Dispatchers.IO`:
 *   serialized writers need one thread, and a shared pool would let two of them interleave.
 * - [engine] is a **Looper-backed** single thread, because that is a measured requirement, not a
 *   preference: `PlayerEngineFactory.create` needs its application looper (P1-3, `core:player`'s
 *   KDoc). A plain `Executor` dispatcher fails at `buildPlayer`.
 *
 * Cost: [main], [single] and [engine] are `by lazy`, so a process that never touches them starts no
 * thread and never asks Android for the main looper. That also keeps this class constructible in a
 * plain JVM unit test (where `Looper` would throw): the test only touches `io` / `default` /
 * `single`, which is all it needs to prove "single parallelism" and "replaceable".
 *
 * **Who is not wired yet:** [engine] is the seam `PlaybackSession` will consume at P2-5 — that
 * session still owns its own `HandlerThread` (P1-3 verified it on the TV, and this round must not
 * touch playback). Both threads carry the same name on purpose so the P2-5 migration is a deletion,
 * not a rename; nothing creates the lazy one until something injects `engine`.
 */
class AndroidDispatcherProvider : DispatcherProvider {

    override val io: CoroutineDispatcher = Dispatchers.IO

    override val default: CoroutineDispatcher = Dispatchers.Default

    /** `immediate` so a UI coroutine that is already on the main thread is not re-dispatched (§10). */
    override val main: CoroutineDispatcher by lazy { Dispatchers.Main.immediate }

    override val single: CoroutineDispatcher by lazy { singleThread(SINGLE_THREAD_NAME) }

    override val engine: CoroutineDispatcher by lazy { looperThread(ENGINE_THREAD_NAME) }

    /** A daemon single-thread executor dressed as a dispatcher: one thread, no leak after a test. */
    private fun singleThread(name: String): CoroutineDispatcher {
        val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }
        return executor.executorAsDispatcher()
    }

    /**
     * A started [HandlerThread] as a dispatcher. `Handler` on its looper is what makes the dispatcher
     * Looper-backed (the P1-3 requirement); the thread stays alive for the process, like the engine
     * thread it will replace, so a future `release()` must not quit it.
     */
    private fun looperThread(name: String): CoroutineDispatcher {
        val thread = HandlerThread(name).apply { start() }
        return Handler(thread.looper).handlerAsDispatcher()
    }

    private companion object {
        /** Serialized writers / state machines (§4.5 C6, §10). */
        const val SINGLE_THREAD_NAME = "iptv-single"

        /** Same name `PlaybackSession` uses, so the P2-5 hand-over changes no log or trace. */
        const val ENGINE_THREAD_NAME = "playback-engine"
    }
}
