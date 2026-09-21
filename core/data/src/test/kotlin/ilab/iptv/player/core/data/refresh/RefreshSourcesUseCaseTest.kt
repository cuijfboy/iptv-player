package ilab.iptv.player.core.data.refresh

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.data.repository.InMemoryStreamRepository
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.domain.repository.StreamRepository
import ilab.iptv.player.core.model.InterruptionReason
import ilab.iptv.player.core.model.RefreshOptions
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.model.RefreshProgress
import ilab.iptv.player.core.model.RefreshTrigger
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.pipeline.PlaybackPrioritySignal
import ilab.iptv.player.core.source.provider.SourceProvider
import ilab.iptv.player.core.source.provider.StreamValidator
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import org.junit.Test

class RefreshSourcesUseCaseTest {

    private val logger = RecordingLogger()
    private val store = ChannelStore()
    private val streams: StreamRepository = InMemoryStreamRepository(store)

    private fun useCase(
        providers: List<SourceProvider>,
        validators: List<StreamValidator> = listOf(FakeStreamValidator()),
        clock: FakeClock = FakeClock(),
        limits: PipelineLimits = PipelineLimits(),
        playing: Boolean = false,
    ) = RefreshSourcesUseCase(
        providers = providers.toSet(),
        validators = validators.toSet(),
        streamRepository = streams,
        limits = limits,
        clock = clock,
        logger = logger,
        sessionIds = FakeSessionIds(),
        playback = PlaybackPrioritySignal { playing },
        // The pipeline is the network/disk half of §6.1, so the injected dispatcher is the real IO
        // pool here: these tests are about phases, budget and cancellation, not about threading.
        // `InjectedDispatchersTest` is where the dispatcher itself is the subject.
        dispatchers = TestDispatcherProvider(kotlinx.coroutines.Dispatchers.IO),
    )

    private fun run(
        useCase: RefreshSourcesUseCase,
        options: RefreshOptions = RefreshOptions(trigger = RefreshTrigger.MANUAL),
    ): List<RefreshProgress> = runBlocking { useCase(options).toList() }

    @Test
    fun `happy path walks the phases and stores every stream`() {
        val validator = FakeStreamValidator()
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider("a", entries = listOf(entry(1, "a"), entry(2, "a"))),
                FakeSourceProvider("b", entries = listOf(entry(3, "b"), entry(4, "b"))),
            ),
            validators = listOf(validator),
        )
        val progress = run(useCase)

        val phases = progress.map { it.phase }
        assertThat(phases).containsAtLeast(RefreshPhase.FETCH, RefreshPhase.PARSE, RefreshPhase.SHALLOW, RefreshPhase.DONE)
        assertThat(phases.indexOf(RefreshPhase.FETCH)).isLessThan(phases.indexOf(RefreshPhase.SHALLOW))
        assertThat(phases.indexOf(RefreshPhase.SHALLOW)).isLessThan(phases.indexOf(RefreshPhase.DONE))

        val done = progress.last()
        assertThat(done.phase).isEqualTo(RefreshPhase.DONE)
        assertThat(done.okCount).isEqualTo(4)
        assertThat(done.failCount).isEqualTo(0)
        assertThat(done.interrupted).isNull()
        assertThat(validator.calls).isEqualTo(4)
        assertThat(logger.count(EventCodes.SRC_REFRESH_START)).isEqualTo(1)
        assertThat(logger.count(EventCodes.SRC_REFRESH_DONE)).isEqualTo(1)
    }

    @Test
    fun `zero candidates finishes cleanly without validating anything`() {
        val validator = FakeStreamValidator()
        val useCase = useCase(
            providers = listOf(FakeSourceProvider("a"), FakeSourceProvider("b")),
            validators = listOf(validator),
        )
        val done = run(useCase).last()
        assertThat(done.phase).isEqualTo(RefreshPhase.DONE)
        assertThat(done.total).isEqualTo(0)
        assertThat(done.okCount).isEqualTo(0)
        assertThat(validator.calls).isEqualTo(0)
    }

    @Test
    fun `all sources failing still reaches DONE with a full failure count`() {
        val validator = FakeStreamValidator()
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider("a", result = AppResult.Err(failure())),
                FakeSourceProvider("b", result = AppResult.Err(failure())),
            ),
            validators = listOf(validator),
        )
        val progress = run(useCase)
        val fetch = progress.last { it.phase == RefreshPhase.FETCH }
        assertThat(fetch.failCount).isEqualTo(2)
        assertThat(fetch.okCount).isEqualTo(0)
        val done = progress.last()
        assertThat(done.phase).isEqualTo(RefreshPhase.DONE)
        assertThat(done.total).isEqualTo(0)
        assertThat(validator.calls).isEqualTo(0)
    }

    @Test
    fun `a failing validator counts as fail, not as a crash`() {
        val validator = FakeStreamValidator(pass = false)
        val useCase = useCase(
            providers = listOf(FakeSourceProvider("a", entries = listOf(entry(1, "a"), entry(2, "a")))),
            validators = listOf(validator),
        )
        val done = run(useCase).last()
        assertThat(done.okCount).isEqualTo(0)
        assertThat(done.failCount).isEqualTo(2)
        assertThat(logger.count(EventCodes.SRC_REFRESH_DONE)).isEqualTo(1)
    }

    @Test
    fun `over budget stops admitting shallow items and reports BUDGET_EXCEEDED`() {
        val clock = FakeClock()
        val validator = FakeStreamValidator()
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider(
                    "a",
                    entries = listOf(entry(1, "a"), entry(2, "a")),
                    clock = clock,
                    advanceClockMs = 5_000, // eats the whole 5 s budget during Fetch
                ),
            ),
            validators = listOf(validator),
            clock = clock,
        )
        val done = run(useCase, RefreshOptions(trigger = RefreshTrigger.FIRST_RUN, budgetMs = 5_000)).last()
        assertThat(done.phase).isEqualTo(RefreshPhase.DONE)
        assertThat(done.interrupted?.reason).isEqualTo(InterruptionReason.BUDGET_EXCEEDED)
        assertThat(done.interrupted?.phase).isEqualTo(RefreshPhase.SHALLOW)
        assertThat(validator.calls).isEqualTo(0)
        assertThat(logger.count(EventCodes.SRC_REFRESH_SKIP)).isEqualTo(1)
    }

    @Test
    fun `a second run skips fresh streams and does no validation`() {
        val validator = FakeStreamValidator()
        val providers = listOf(FakeSourceProvider("a", entries = listOf(entry(1, "a"), entry(2, "a"))))
        val clock = FakeClock()
        val useCase = useCase(providers = providers, validators = listOf(validator), clock = clock)

        val first = run(useCase).last()
        assertThat(first.okCount).isEqualTo(2)
        assertThat(validator.calls).isEqualTo(2)

        val second = run(useCase).last()
        assertThat(second.phase).isEqualTo(RefreshPhase.DONE)
        assertThat(second.okCount).isEqualTo(0)
        assertThat(second.total).isEqualTo(2)
        assertThat(validator.calls).isEqualTo(2) // unchanged: everything was fresh
    }

    @Test
    fun `cancelling the collector stops the run without reaching DONE`() {
        val collected = mutableListOf<RefreshProgress>()
        runBlocking {
            val useCase = useCase(
                providers = listOf(
                    FakeSourceProvider("a", entries = listOf(entry(1, "a"))),
                    FakeSourceProvider("b", delayMs = 10_000),
                ),
            )
            val job = launch {
                useCase(RefreshOptions(trigger = RefreshTrigger.MANUAL)).collect { collected += it }
            }
            delay(200)
            job.cancelAndJoin()
            assertThat(job.isCancelled).isTrue()
        }
        assertThat(collected.map { it.phase }).doesNotContain(RefreshPhase.DONE)
    }
}
