package ilab.iptv.player.core.data.dispatchers

import ilab.iptv.player.core.common.DispatcherProvider
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * A [DispatcherProvider] the test owns (docs/02 §10: the whole point of injecting dispatchers is
 * that a test can hand in its own).
 *
 * Default is one [UnconfinedTestDispatcher] for every member: work runs eagerly on the calling
 * thread, so a `runBlocking`-style test neither needs a scheduler pump nor real time. Pass a named
 * single thread ([namedThread]) when the assertion is "this really left the caller's thread".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatcherProvider(
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
) : DispatcherProvider {

    override val io: CoroutineDispatcher get() = dispatcher

    override val default: CoroutineDispatcher get() = dispatcher

    override val main: CoroutineDispatcher get() = dispatcher

    override val single: CoroutineDispatcher get() = dispatcher

    override val engine: CoroutineDispatcher get() = dispatcher
}

/**
 * A real, named single thread dressed as a dispatcher — the cheapest way to prove *which* thread the
 * code under test used (`Thread.currentThread().name` inside a fake).
 */
fun namedThread(name: String): CoroutineDispatcher {
    val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }
    return executor.asCoroutineDispatcher()
}
