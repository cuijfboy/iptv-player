package ilab.iptv.player.core.data.refresh

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.data.test
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.data.mapper.ChannelMapper
import ilab.iptv.player.core.data.mapper.MappedCatalog
import ilab.iptv.player.core.data.repository.RoomStreamRepository
import ilab.iptv.player.core.source.normalize.PlaylistNormalizer
import ilab.iptv.player.core.model.DeviceProfile
import ilab.iptv.player.core.model.RefreshOptions
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.model.RefreshTrigger
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamOutcome
import ilab.iptv.player.core.domain.scoring.DefaultScorer
import ilab.iptv.player.core.domain.selection.DefaultStreamSelector
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.pipeline.PlaybackPrioritySignal
import ilab.iptv.player.core.source.provider.SourceProvider
import ilab.iptv.player.core.source.provider.StreamValidator
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 卡 REFRESH-PERSIST-1: the refresh pipeline over the **real** Room stack (Robolectric) — the joint
 * the off-device in-memory tests could not see, because a `StateFlow` has no foreign key to violate.
 *
 * The card's three claims live here:
 * 1. **[reproduces] the failure.** `stream.channel_id -> channel.id` is enforced, and writing a stream
 *    for a channel that is not stored is refused and logged `DB_FAIL` (`written:0`) — exactly the
 *    `DB_FAIL FOREIGN KEY constraint failed … written:0` the snapshot QA saw on the TV, produced here
 *    through the production `RoomStreamRepository`.
 * 2. **[fixes] it.** A refresh run over sources whose channels are not in the store writes both the
 *    channels and their streams into Room, keeps the foreign key satisfied, and leaves a stream the
 *    playback path can select (`StreamRepository.candidates`).
 * 3. **[keeps] the other two semantics.** A second identical run is idempotent (same rows, same ids),
 *    and a run whose sources all fail changes nothing in the table.
 *
 * 卡 STREAM-ID-1 adds the other half of the same joint: the *stream* ids the pipeline mints in memory
 * are not the stored ones either, so a shallow verdict addressed by them lands on a stranger. The
 * tests at the bottom reproduce that and pin the fix (one `play_history` row per probed stream, on
 * that stream's own row, with the pre-existing rows left untouched).
 */
@RunWith(AndroidJUnit4::class)
class RefreshPersistRoomTest {

    private val device = DeviceProfile(
        abi = "arm64-v8a",
        sdk = 30,
        ramMb = 2048,
        audioPassthrough = emptySet(),
        maxWidth = 1920,
        maxHeight = 1080,
        maxFrameRate = 60f,
    )

    private val openDatabases = mutableListOf<ilab.iptv.player.core.database.IptvDatabase>()

    private fun rig(): RoomFixtures.Rig {
        val rig = RoomFixtures.Rig(RoomFixtures.inMemoryDatabase())
        openDatabases += rig.database
        return rig
    }

    @After
    fun tearDown() {
        openDatabases.forEach { it.close() }
        openDatabases.clear()
    }

    private fun useCase(
        rig: RoomFixtures.Rig,
        logger: ilab.iptv.player.core.common.Logger,
        providers: List<SourceProvider>,
        validators: List<StreamValidator>,
        clock: FakeClock = FakeClock(),
    ) = RefreshSourcesUseCase(
        providers = providers.toSet(),
        validators = validators.toSet(),
        streamRepository = rig.streams,
        channelRepository = rig.channels,
        catalogSink = rig.writer,
        scorer = DefaultScorer(),
        selector = DefaultStreamSelector(),
        device = device,
        limits = PipelineLimits(),
        clock = clock,
        logger = logger,
        sessionIds = FakeSessionIds(),
        playback = PlaybackPrioritySignal { false },
        dispatchers = TestDispatcherProvider(),
    )

    private fun run(useCase: RefreshSourcesUseCase): List<ilab.iptv.player.core.model.RefreshProgress> =
        runBlocking { useCase(RefreshOptions(trigger = RefreshTrigger.MANUAL)).toList() }

    /** The reported failure, reproduced through the production repository: a stream with no channel. */
    @Test
    fun `a stream whose channel was never stored is refused and logged DB_FAIL`() = test {
        val rig = rig()
        val logger = RoomFixtures.RecordingLogger()
        val repository = RoomStreamRepository(rig.database, rig.database.streamDao(), logger)

        repository.upsertAll(listOf(orphanStream(channelId = 4_242L)))

        assertThat(rig.database.streamDao().allIdentities()).isEmpty()
        assertThat(logger.codes).contains(EventCodes.DB_FAIL)
        assertThat(rig.database.streamDao().countForChannel(4_242L)).isEqualTo(0)
    }

    @Test
    fun `refresh writes channels and streams into Room with the foreign key satisfied`() = test {
        val rig = rig()
        val logger = RoomFixtures.RecordingLogger()
        val useCase = useCase(
            rig = rig,
            logger = logger,
            providers = listOf(
                FakeSourceProvider(
                    "src",
                    entries = listOf(
                        namedEntry("Alpha TV", "http://alpha.invalid/live.m3u8", "src"),
                        namedEntry("Beta TV", "http://beta.invalid/live.m3u8", "src"),
                    ),
                ),
            ),
            validators = listOf(FakeStreamValidator(), FakeDeepValidator.passing()),
        )

        val progress = run(useCase)

        // The run reached DONE with no storage failure — the FK rejection is gone.
        assertThat(progress.last().phase).isEqualTo(RefreshPhase.DONE)
        assertThat(logger.codes).doesNotContain(EventCodes.DB_FAIL)

        // Both channels and both streams are in Room, and the channels are the ones the sources named.
        assertThat(rig.database.channelDao().count()).isEqualTo(2)
        val storedChannels = rig.database.channelDao().all()
        assertThat(storedChannels.map { it.name }).containsExactly("Alpha TV", "Beta TV")
        assertThat(rig.database.streamDao().allIdentities()).hasSize(2)

        // The foreign key holds: every stream points at a channel that exists.
        val channelIds = storedChannels.map { it.id }.toSet()
        val streamChannelIds = rig.database.streamDao().allIdentities().map { it.channelId }.toSet()
        assertThat(streamChannelIds).isNotEmpty()
        assertThat(channelIds).containsAtLeastElementsIn(streamChannelIds)

        // "Refresh then play": the query the playback path makes returns a selectable stream.
        // `nameKey` strips whitespace and folds case (`NameNormalizer`), `groupKey` likewise: "Group"
        // -> "group".
        val selected = rig.database.channelDao().findByKey("alphatv", "group")!!
        assertThat(rig.streams.candidates(selected.id)).isNotEmpty()
    }

    @Test
    fun `a repeated refresh is idempotent and keeps the stored ids`() = test {
        val rig = rig()
        val logger = RoomFixtures.RecordingLogger()
        val entries = listOf(
            namedEntry("Alpha TV", "http://alpha.invalid/live.m3u8", "src"),
            namedEntry("Beta TV", "http://beta.invalid/live.m3u8", "src"),
        )
        fun build() = useCase(
            rig = rig,
            logger = logger,
            providers = listOf(FakeSourceProvider("src", entries = entries)),
            validators = listOf(FakeStreamValidator(), FakeDeepValidator.passing()),
            clock = FakeClock(),
        )

        run(build())
        val channelIdsAfterFirst = rig.database.channelDao().all().map { it.id }.toSet()
        val streamIdsAfterFirst = rig.database.streamDao().allIdentities().map { it.id }.toSet()

        run(build())

        // Same rows, same ids: the upsert keys are `(name_key, group_key)` / `(channel_id, url_hash)`.
        assertThat(rig.database.channelDao().count()).isEqualTo(2)
        assertThat(rig.database.streamDao().allIdentities()).hasSize(2)
        assertThat(rig.database.channelDao().all().map { it.id }.toSet()).isEqualTo(channelIdsAfterFirst)
        assertThat(rig.database.streamDao().allIdentities().map { it.id }.toSet()).isEqualTo(streamIdsAfterFirst)
        assertThat(logger.codes).doesNotContain(EventCodes.DB_FAIL)
    }

    @Test
    fun `a refresh whose sources all fail leaves the stored catalog untouched`() = test {
        val rig = rig()
        val logger = RoomFixtures.RecordingLogger()
        // A stored catalog (as if the bundled snapshot had seeded it), one channel with one stream.
        runBlocking {
            rig.writer.write(
                ilab.iptv.player.core.data.mapper.ChannelMapper.toDomain(
                    ilab.iptv.player.core.source.normalize.PlaylistNormalizer.normalize(
                        listOf(namedEntry("Snapshot One", "http://snap.invalid/1.m3u8", "snapshot")),
                    ),
                ),
                1_000L,
            )
        }
        val channelsBefore = rig.database.channelDao().all().map { it.name }
        val streamsBefore = rig.database.streamDao().allIdentities().size

        val useCase = useCase(
            rig = rig,
            logger = logger,
            providers = listOf(FakeSourceProvider("a", result = ilab.iptv.player.core.common.AppResult.Err(failure()))),
            validators = listOf(FakeStreamValidator()),
        )
        val progress = run(useCase)

        assertThat(progress.last().phase).isEqualTo(RefreshPhase.DONE)
        assertThat(rig.database.channelDao().all().map { it.name }).isEqualTo(channelsBefore)
        assertThat(rig.database.streamDao().allIdentities()).hasSize(streamsBefore)
    }

    /** One `stream` row for a channel id no channel row carries — the shape the bug produced. */
    private fun orphanStream(channelId: Long): Stream = Stream(
        id = 0,
        channelId = channelId,
        url = "http://orphan.invalid/live.m3u8",
        urlHash = "orphan-hash",
        userAgent = null,
        referrer = null,
        sourceId = "src",
        quality = null,
        videoCodec = null,
        audioCodec = null,
        width = 0,
        height = 0,
        score = 0,
        priority = 0,
        lastOkAtMs = null,
        lastCheckAtMs = null,
        failCount = 0,
        lastError = null,
        disabled = false,
    )

    /** The catalog one small playlist maps to — the same pure path the pipeline runs before writing. */
    private fun catalogOf(vararg entries: Pair<String, String>): MappedCatalog =
        ChannelMapper.toDomain(
            PlaylistNormalizer.normalize(entries.map { (name, url) -> namedEntry(name, url) }),
        )

    // --- 卡 STREAM-ID-1: the per-load stream id vs. the stored one ---------------------------------

    /**
     * **The reproduction.** `ChannelMapper` mints stream ids from a per-load counter, and the store
     * keeps the ids it already handed out, so on a store that already held a catalog the id a refresh
     * mints for its *first* stream is the id of a row that belongs to a different channel. Addressing
     * a verdict by that id — which is what the shallow stage did before this card — stamps a stranger:
     * the re-probed channel's health and its `play_history` row land on the pre-existing stream.
     */
    @Test
    fun `the id the pipeline mints in memory is not the stored stream id`() = test {
        val rig = rig()
        // A stored catalog, as if the bundled snapshot had seeded it: one channel, stream row id 1.
        runBlocking { rig.writer.write(catalogOf("Snapshot One" to "http://snap.invalid/1.m3u8"), 1_000L) }
        val seeded = rig.database.streamDao().allIdentities().single()
        assertThat(seeded.id).isEqualTo(1L)

        // A later refresh of an unrelated source mints its stream ids from 1 again …
        val mapped = catalogOf("Refreshed TV" to "http://new.invalid/live.m3u8")
        val memoryId = mapped.streams.single().id
        assertThat(memoryId).isEqualTo(1L)

        // … and 1 is the snapshot's row, so the "shallow verdict" lands on the wrong channel's stream.
        rig.streams.recordOutcome(memoryId, StreamOutcome(streamId = memoryId, ok = true, atMs = 5_000L))

        assertThat(rig.database.streamDao().getById(seeded.id)!!.lastOkAt).isEqualTo(5_000L)
        assertThat(rig.database.playHistoryDao().recent(10).single().channelId).isEqualTo(seeded.channelId)
    }

    /**
     * **The fix, success path.** With the ids aligned after the write, each shallow verdict lands on
     * the row of the stream that was actually probed: one `play_history` row per refreshed stream,
     * the health the scorer reads back (`health(streamId)`) is the health the shallow stage wrote, and
     * the rows the store already held keep no verdict and no stamp.
     */
    @Test
    fun `a shallow verdict lands on the refreshed stream's own row, not on a pre-existing one`() = test {
        val rig = rig()
        val logger = RoomFixtures.RecordingLogger()
        runBlocking { rig.writer.write(catalogOf("Snapshot One" to "http://snap.invalid/1.m3u8"), 1_000L) }
        val snapshotStreamId = rig.database.streamDao().allIdentities().single().id

        val useCase = useCase(
            rig = rig,
            logger = logger,
            providers = listOf(
                FakeSourceProvider(
                    "src",
                    entries = listOf(
                        namedEntry("Alpha TV", "http://alpha.invalid/live.m3u8", "src"),
                        namedEntry("Beta TV", "http://beta.invalid/live.m3u8", "src"),
                    ),
                ),
            ),
            validators = listOf(FakeStreamValidator(), FakeDeepValidator.passing()),
        )
        run(useCase)

        val refreshed = rig.database.streamDao().allIdentities().filter { it.id != snapshotStreamId }
        assertThat(refreshed).hasSize(2)

        // One verdict each, addressed at its own row — the per-load ids (1, 2) are already taken.
        val history = rig.database.playHistoryDao().recent(50)
        assertThat(history).hasSize(2)
        assertThat(history.map { it.streamId }.toSet()).isEqualTo(refreshed.map { it.id }.toSet())
        assertThat(history.map { it.channelId }.toSet())
            .isEqualTo(refreshed.map { it.channelId }.toSet())

        // What the shallow stage wrote is what the scorer read back, on the same row.
        refreshed.forEach { assertThat(rig.streams.health(it.id).attempts).isEqualTo(1) }

        // The pre-existing row was never probed, so it carries no stamp and no health.
        assertThat(rig.streams.health(snapshotStreamId).attempts).isEqualTo(0)
        val untouched = rig.database.streamDao().getById(snapshotStreamId)!!
        assertThat(untouched.lastCheckAt).isNull()
        assertThat(untouched.lastOkAt).isNull()
        assertThat(logger.codes).doesNotContain(EventCodes.DB_FAIL)
    }

    /** **The fix, failure path** — and 失败不改表: a failing verdict is recorded against its own row. */
    @Test
    fun `a shallow failure marks the refreshed stream's own row and leaves the stored rows alone`() = test {
        val rig = rig()
        val logger = RoomFixtures.RecordingLogger()
        runBlocking { rig.writer.write(catalogOf("Snapshot One" to "http://snap.invalid/1.m3u8"), 1_000L) }
        val snapshot = rig.database.streamDao().allIdentities().single()

        val useCase = useCase(
            rig = rig,
            logger = logger,
            providers = listOf(
                FakeSourceProvider(
                    "src",
                    entries = listOf(namedEntry("Alpha TV", "http://alpha.invalid/live.m3u8", "src")),
                ),
            ),
            validators = listOf(FakeStreamValidator(pass = false), FakeDeepValidator.passing()),
        )
        run(useCase)

        val refreshed = rig.database.streamDao().allIdentities().single { it.id != snapshot.id }
        val history = rig.database.playHistoryDao().recent(50).single()
        assertThat(history.streamId).isEqualTo(refreshed.id)
        assertThat(history.result).isNotEqualTo("OK")
        assertThat(rig.database.streamDao().getById(refreshed.id)!!.failCount).isEqualTo(1)

        val untouched = rig.database.streamDao().getById(snapshot.id)!!
        assertThat(untouched.lastCheckAt).isNull()
        assertThat(untouched.failCount).isEqualTo(0)
        assertThat(rig.streams.health(snapshot.id).attempts).isEqualTo(0)
    }
}
