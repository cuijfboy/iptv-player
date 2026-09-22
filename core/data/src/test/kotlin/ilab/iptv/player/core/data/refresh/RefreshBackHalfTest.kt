package ilab.iptv.player.core.data.refresh

import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.data.repository.InMemoryStreamRepository
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.domain.repository.StreamRepository
import ilab.iptv.player.core.domain.scoring.DefaultScorer
import ilab.iptv.player.core.domain.selection.DefaultStreamSelector
import ilab.iptv.player.core.model.DeviceProfile
import ilab.iptv.player.core.model.InterruptionReason
import ilab.iptv.player.core.model.RefreshOptions
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.model.RefreshProgress
import ilab.iptv.player.core.model.RefreshTrigger
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.pipeline.PlaybackPrioritySignal
import ilab.iptv.player.core.source.provider.SourceProvider
import ilab.iptv.player.core.source.provider.StreamValidator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The P2-4b back half (Deep → Score → Select → Persist) driven end to end through the use case with
 * fake providers and fake validators — no network, no Android.
 */
class RefreshBackHalfTest {

    private val logger = RecordingLogger()
    private val store = ChannelStore()
    private val streams: StreamRepository = InMemoryStreamRepository(store)
    private val channels = FakeChannelRepository(store)

    private val device = DeviceProfile(
        abi = "arm64-v8a",
        sdk = 30,
        ramMb = 2048,
        audioPassthrough = emptySet(),
        maxWidth = 1920,
        maxHeight = 1080,
        maxFrameRate = 60f,
    )

    private fun useCase(
        providers: List<SourceProvider>,
        validators: List<StreamValidator>,
        clock: FakeClock = FakeClock(),
        limits: PipelineLimits = PipelineLimits(),
    ) = RefreshSourcesUseCase(
        providers = providers.toSet(),
        validators = validators.toSet(),
        streamRepository = streams,
        channelRepository = channels,
        catalogSink = store,
        scorer = DefaultScorer(),
        selector = DefaultStreamSelector(),
        device = device,
        limits = limits,
        clock = clock,
        logger = logger,
        sessionIds = FakeSessionIds(),
        playback = PlaybackPrioritySignal { false },
        dispatchers = TestDispatcherProvider(),
    )

    private fun run(
        useCase: RefreshSourcesUseCase,
        options: RefreshOptions = RefreshOptions(trigger = RefreshTrigger.MANUAL),
    ): List<RefreshProgress> = runBlocking { useCase(options).toList() }

    private fun allStreams(): List<Stream> = store.snapshotStreams()

    @Test
    fun `the deep stage probes exactly the streams the shallow stage passed`() {
        val deep = FakeDeepValidator.passing()
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider(
                    "a",
                    entries = listOf(namedEntry("CCTV-1", "http://s.invalid/1.m3u8")),
                ),
            ),
            validators = listOf(FakeStreamValidator(pass = true), deep),
        )
        val progress = run(useCase)

        assertThat(deep.calls).hasSize(1)
        val phases = progress.map { it.phase }
        assertThat(phases.indexOf(RefreshPhase.SHALLOW)).isLessThan(phases.indexOf(RefreshPhase.DEEP))
        assertThat(phases.indexOf(RefreshPhase.DEEP)).isLessThan(phases.indexOf(RefreshPhase.SCORE))
        assertThat(phases.indexOf(RefreshPhase.SCORE)).isLessThan(phases.indexOf(RefreshPhase.SELECT))
        assertThat(phases.indexOf(RefreshPhase.SELECT)).isLessThan(phases.indexOf(RefreshPhase.PERSIST))
        assertThat(phases.last()).isEqualTo(RefreshPhase.DONE)
        assertThat(logger.count(EventCodes.VAL_DEEP_OK)).isEqualTo(0) // the fake never logs
        assertThat(logger.count(EventCodes.SRC_SCORE)).isEqualTo(1)
        assertThat(logger.count(EventCodes.SRC_SELECT)).isEqualTo(1)
        assertThat(logger.count(EventCodes.DB_UPSERT)).isEqualTo(1) // the selection write
    }

    @Test
    fun `a shallow failure is never deep probed`() {
        val deep = FakeDeepValidator.passing()
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider("a", entries = listOf(namedEntry("CCTV-1", "http://s.invalid/1.m3u8"))),
            ),
            validators = listOf(FakeStreamValidator(pass = false), deep),
        )
        run(useCase)
        assertThat(deep.calls).isEmpty()
        val row = allStreams().single()
        assertThat(row.disabled).isTrue()
        assertThat(row.failCount).isEqualTo(1)
    }

    @Test
    fun `a deep pass writes resolution, codecs, score and the healthy stamp`() {
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider("a", entries = listOf(namedEntry("CCTV-1", "http://s.invalid/1.m3u8"))),
            ),
            validators = listOf(FakeStreamValidator(pass = true), FakeDeepValidator.passing()),
        )
        run(useCase)

        val row = allStreams().single()
        assertThat(row.videoCodec).isEqualTo("video/avc")
        assertThat(row.audioCodec).isEqualTo("audio/mp4a-latm")
        assertThat(row.width).isEqualTo(1920)
        assertThat(row.height).isEqualTo(1080)
        assertThat(row.score).isEqualTo(100)
        assertThat(row.disabled).isFalse()
        assertThat(row.lastOkAtMs).isNotNull()
        assertThat(row.failCount).isEqualTo(0)
    }

    @Test
    fun `a deep failure disables the stream and clears the shallow stage's healthy stamp`() {
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider("a", entries = listOf(namedEntry("CCTV-1", "http://s.invalid/dead.m3u8"))),
            ),
            validators = listOf(FakeStreamValidator(pass = true), FakeDeepValidator.passing()),
        )
        run(useCase)

        val row = allStreams().single()
        assertThat(row.disabled).isTrue()
        assertThat(row.lastOkAtMs).isNull()
        assertThat(row.failCount).isEqualTo(1)
        assertThat(row.lastError).isEqualTo("HTTP_SERVER")
    }

    @Test
    fun `one channel keeps a primary and at most two backups`() {
        val entries = (1..4).map { namedEntry("CCTV-1", "http://s.invalid/$it.m3u8") }
        val useCase = useCase(
            providers = listOf(FakeSourceProvider("a", entries = entries)),
            validators = listOf(FakeStreamValidator(pass = true), FakeDeepValidator.passing()),
        )
        run(useCase)

        val rows = allStreams()
        assertThat(rows).hasSize(4)
        assertThat(rows.count { !it.disabled }).isEqualTo(3)
        assertThat(rows.count { it.disabled }).isEqualTo(1)
    }

    @Test
    fun `a channel with no usable stream stays in the catalogue, marked unavailable`() {
        // The catalogue entry itself comes from the loader/import (docs/02 §5.2); the refresh must
        // never remove it. Seeding it here is what "stays visible" is measured against.
        // The stored row carries the §5.1 identity keys (`name_key`, `group_key`), the way the loader
        // or an import writes it. The refresh resolves its own "CCTV-1" (group "Group") onto this row
        // through those keys (卡 REFRESH-PERSIST-1), so the row is the one it keeps — and it must,
        // because "a refresh never removes a channel" is the other half of what this test measures.
        store.replaceAll(
            channels = listOf(
                channelRow(1, "CCTV-1", 0).copy(
                    nameKey = ilab.iptv.player.core.source.normalize.Keys.nameKey("CCTV-1"),
                    groupKey = ilab.iptv.player.core.source.normalize.Keys.groupKey("Group"),
                ),
            ),
            streams = emptyList(),
        )
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider(
                    "a",
                    entries = listOf(
                        namedEntry("CCTV-1", "http://s.invalid/dead-1.m3u8"),
                        namedEntry("CCTV-1", "http://s.invalid/dead-2.m3u8"),
                    ),
                ),
            ),
            validators = listOf(FakeStreamValidator(pass = true), FakeDeepValidator.passing()),
        )
        val progress = run(useCase)

        assertThat(store.channels.value).hasSize(1)
        assertThat(allStreams()).hasSize(2)
        assertThat(allStreams().none { !it.disabled }).isTrue()
        val select = progress.last { it.phase == RefreshPhase.SELECT }
        assertThat(select.failCount).isEqualTo(1)
        assertThat(select.okCount).isEqualTo(0)
    }

    @Test
    fun `a second run reuses stored scores and re-probes nothing`() {
        val deep = FakeDeepValidator.passing()
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider("a", entries = listOf(namedEntry("CCTV-1", "http://s.invalid/1.m3u8"))),
            ),
            validators = listOf(FakeStreamValidator(pass = true), deep),
        )
        run(useCase)
        val first = allStreams().single()
        assertThat(deep.calls).hasSize(1)

        run(useCase)
        val second = allStreams().single()
        assertThat(deep.calls).hasSize(1) // unchanged: the stream was fresh
        assertThat(second.score).isEqualTo(first.score)
        assertThat(second.disabled).isEqualTo(first.disabled)
        assertThat(second.lastOkAtMs).isEqualTo(first.lastOkAtMs)
        assertThat(second.lastCheckAtMs).isEqualTo(first.lastCheckAtMs)
    }

    /**
     * The deep stage must not invent a verdict when the deadline closes — but the *shallow* verdict
     * this run already recorded stays: it is the checkpoint docs/02 §6.1's 断点续跑 rule is built on,
     * and the final persist deliberately does not write the pre-shallow snapshot back over it (P2-5
     * found this by driving a kill/resume pair; see `docs/05-过程记录/26-P2-5定时刷新验证.md`).
     */
    @Test
    fun `a deadline that closes before the deep stage leaves the deep columns untouched and keeps the shallow verdict`() {
        val clock = FakeClock()
        val deep = FakeDeepValidator.passing()
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider("a", entries = listOf(namedEntry("CCTV-1", "http://s.invalid/1.m3u8"))),
            ),
            // Shallow passes but eats the whole budget, so Deep cannot admit its item.
            validators = listOf(FakeStreamValidator(pass = true, clock = clock, advanceClockMs = 5_000), deep),
            clock = clock,
        )
        val progress = run(useCase, RefreshOptions(trigger = RefreshTrigger.FIRST_RUN, budgetMs = 5_000))

        assertThat(deep.calls).isEmpty()
        val done = progress.last()
        assertThat(done.phase).isEqualTo(RefreshPhase.DONE)
        assertThat(done.interrupted?.reason).isEqualTo(InterruptionReason.BUDGET_EXCEEDED)
        assertThat(done.interrupted?.phase).isEqualTo(RefreshPhase.DEEP)
        val row = allStreams().single()
        assertThat(row.disabled).isFalse()
        assertThat(row.score).isEqualTo(0)
        assertThat(row.lastCheckAtMs).isEqualTo(clock.nowMs())
    }

    @Test
    fun `with no deep validator the back half steps aside instead of scoring blind`() {
        val useCase = useCase(
            providers = listOf(
                FakeSourceProvider("a", entries = listOf(namedEntry("CCTV-1", "http://s.invalid/1.m3u8"))),
            ),
            validators = listOf(FakeStreamValidator(pass = true)),
        )
        run(useCase)

        val row = allStreams().single()
        assertThat(row.score).isEqualTo(0)
        assertThat(row.disabled).isFalse()
        assertThat(row.videoCodec).isNull()
        assertThat(logger.count(EventCodes.SRC_SCORE)).isEqualTo(0)
        assertThat(logger.count(EventCodes.SRC_SELECT)).isEqualTo(0)
    }

    @Test
    fun `the on-demand path re-probes and re-selects a single channel`() {
        store.replaceAll(
            channels = listOf(channelRow(1, "CCTV-1", 2), channelRow(2, "CCTV-2", 1)),
            streams = listOf(
                streamRow(id = 11, channelId = 1, url = "http://s.invalid/11.m3u8"),
                streamRow(id = 12, channelId = 1, url = "http://s.invalid/12.m3u8"),
                streamRow(id = 21, channelId = 2, url = "http://s.invalid/21.m3u8"),
            ),
        )
        val deep = FakeDeepValidator.passing()
        val useCase = useCase(providers = emptyList(), validators = listOf(deep))

        val progress = run(
            useCase,
            RefreshOptions(trigger = RefreshTrigger.ON_DEMAND_SINGLE_CHANNEL, channelIdOnly = 1),
        )

        assertThat(deep.calls).containsExactly("http://s.invalid/11.m3u8", "http://s.invalid/12.m3u8")
        val untouched = allStreams().single { it.id == 21L }
        assertThat(untouched.score).isEqualTo(0)
        assertThat(untouched.lastCheckAtMs).isNull()
        val reprobed = allStreams().single { it.id == 11L }
        assertThat(reprobed.score).isEqualTo(100)
        assertThat(progress.last().phase).isEqualTo(RefreshPhase.DONE)
    }

    @Test
    fun `the deep evidence mapper reads the probe contract and tolerates junk`() {
        val fields = DeepEvidence.of(
            mapOf(
                "vcodec" to "video/hevc",
                "acodec" to "audio/ac3",
                "w" to 3840,
                "h" to "2160",
                "ms" to 1_500L,
            ),
        )
        assertThat(fields.videoCodec).isEqualTo("video/hevc")
        assertThat(fields.audioCodec).isEqualTo("audio/ac3")
        assertThat(fields.width).isEqualTo(3840)
        assertThat(fields.height).isEqualTo(2160)
        assertThat(fields.costMs).isEqualTo(1_500)

        val empty = DeepEvidence.of(mapOf("vcodec" to "", "w" to "nonsense"))
        assertThat(empty.videoCodec).isNull()
        assertThat(empty.width).isEqualTo(0)
        assertThat(empty.costMs).isNull()
    }
}
