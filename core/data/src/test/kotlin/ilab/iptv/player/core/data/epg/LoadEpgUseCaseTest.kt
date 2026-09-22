package ilab.iptv.player.core.data.epg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.data.mapper.PersistenceMapper
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.database.entity.ChannelEntity
import ilab.iptv.player.core.database.entity.EpgSourceEntity
import ilab.iptv.player.core.epg.EpgAliases
import ilab.iptv.player.core.epg.EpgMatcher
import ilab.iptv.player.core.epg.EpgProvider
import ilab.iptv.player.core.epg.XmltvStream
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.source.normalize.Keys
import ilab.iptv.player.core.source.pipeline.PlaybackPrioritySignal
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole §6.3 pipeline end to end, in memory: a fake provider hands back XMLTV text, the real parser
 * streams it, the real matcher binds channels, Room stores the rows, and the port answers now/next.
 *
 * This is the test that would catch the failures the parts cannot see on their own — a row written
 * under the wrong `epg_channel_id`, a match that is not persisted, a retention window that is applied
 * to the wrong instant, or a channel whose programme never becomes visible to the info bar.
 */
@RunWith(AndroidJUnit4::class)
class LoadEpgUseCaseTest {

    private lateinit var database: IptvDatabase
    private lateinit var repository: RoomEpgRepository
    private lateinit var logger: RoomFixtures.RecordingLogger
    private lateinit var clock: Clock
    private lateinit var useCase: LoadEpgUseCase

    /**
     * The programme times are relative to this instant, so the "inside the retention window" cases are
     * about the rule rather than about a date someone has to recompute later.
     */
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = RoomFixtures.inMemoryDatabase()
        clock = RoomFixtures.clock(now)
        repository = RoomEpgRepository(database.channelDao(), database.programmeDao(), clock)
        logger = RoomFixtures.RecordingLogger()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun channel(name: String, tvgId: String?, epgChannelId: String? = null): Long =
        database.channelDao().insert(
            ChannelEntity(
                id = 0,
                name = name,
                nameKey = Keys.nameKey(name),
                tvgId = tvgId,
                groupKey = "cctv",
                groupTitle = "央视",
                logo = null,
                channelNo = null,
                favorite = false,
                hidden = false,
                sortOrder = 0,
                epgChannelId = epgChannelId,
                epgMatch = if (epgChannelId == null) "NONE" else "MANUAL",
                createdAt = now,
                updatedAt = now,
            ),
        )

    /**
     * Builds the use case and registers each provider as an enabled `epg_source` row, which is how the
     * real app reaches them: the table is the configuration and the provider set is the code. A test
     * that wants the seeding/disable path itself writes the rows first (see the enable/disable test).
     */
    private suspend fun buildUseCase(
        vararg providers: EpgProvider,
        playback: PlaybackPrioritySignal = PlaybackPrioritySignal { false },
    ): LoadEpgUseCase {
        providers.forEach { provider ->
            if (database.epgSourceDao().all().none { it.id == provider.id }) {
                database.epgSourceDao().upsert(
                    EpgSourceEntity(
                        id = provider.id,
                        label = provider.label,
                        url = "https://example.invalid/${provider.id}.xml",
                        enabled = 1,
                        lastFetchAt = null,
                        lastResult = null,
                    ),
                )
            }
        }
        return LoadEpgUseCase(
            providers = providers.toSet(),
            repository = repository,
            channelDao = database.channelDao(),
            epgSourceDao = database.epgSourceDao(),
            programmeDao = database.programmeDao(),
            matcher = EpgMatcher(nameKey = Keys::nameKey, aliases = EpgAliases.BUILT_IN),
            clock = clock,
            logger = logger,
            dispatchers = TestDispatcherProvider(),
            playback = playback,
        )
    }

    private class FakeProvider(
        override val id: String,
        private val body: String?,
        private val failure: AppError? = null,
        /** Runs before the body is handed back, so a test can flip the playback signal mid-run. */
        private val onFetch: (() -> Unit)? = null,
    ) : EpgProvider {
        override val label: String = id
        var fetches: Int = 0

        override suspend fun fetch(clock: Clock): AppResult<XmltvStream> {
            fetches++
            onFetch?.invoke()
            if (failure != null) return AppResult.Err(failure)
            val bytes = body!!.toByteArray()
            return AppResult.Ok(
                XmltvStream(id, bytes.size.toLong(), ByteArrayInputStream(bytes)),
            )
        }
    }

    private fun guide(vararg rows: String): String = guideOf("CCTV1.cn", "CCTV-1 综合", *rows)

    /** The same document under a chosen guide id / display name, for the two-source cases. */
    private fun guideOf(channelId: String, displayName: String, vararg rows: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
        append("<channel id=\"$channelId\"><display-name>$displayName</display-name></channel>\n")
        rows.forEach { append(it).append('\n') }
        append("</tv>\n")
    }

    /** [count] hourly programmes starting at `now`, all inside the `[now-6h, now+48h]` window. */
    private fun slots(epgChannel: String, count: Int): List<String> =
        (0 until count).map { index ->
            programme(
                now + index * 3_600_000L,
                now + (index + 1) * 3_600_000L,
                "P$index",
                epgChannel = epgChannel,
            )
        }

    private fun programme(
        startMs: Long,
        stopMs: Long,
        title: String,
        epgChannel: String = "CCTV1.cn",
    ): String {
        fun stamp(ms: Long): String {
            val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            utc.timeInMillis = ms
            return String.format(
                java.util.Locale.US,
                "%04d%02d%02d%02d%02d%02d +0000",
                utc.get(java.util.Calendar.YEAR),
                utc.get(java.util.Calendar.MONTH) + 1,
                utc.get(java.util.Calendar.DAY_OF_MONTH),
                utc.get(java.util.Calendar.HOUR_OF_DAY),
                utc.get(java.util.Calendar.MINUTE),
                utc.get(java.util.Calendar.SECOND),
            )
        }
        return "<programme start=\"${stamp(startMs)}\" stop=\"${stamp(stopMs)}\" " +
            "channel=\"$epgChannel\"><title>$title</title></programme>"
    }

    @Test
    fun `a guide is fetched, matched, stored and readable as now slash next`() = runBlocking<Unit> {
        val channelId = channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        useCase = buildUseCase(
            FakeProvider(
                id = "test",
                body = guide(
                    programme(now - 30 * 60_000L, now + 30 * 60_000L, "正在播"),
                    programme(now + 30 * 60_000L, now + 60 * 60_000L, "接下来"),
                ),
            ),
        )

        val report = (useCase() as AppResult.Ok).value

        assertThat(report.providers).isEqualTo(1)
        assertThat(report.programmes).isEqualTo(2)
        assertThat(report.coverage.matched).isEqualTo(1)
        assertThat(report.coverage.total).isEqualTo(1)

        // The match is persisted, not just reported.
        val stored = database.channelDao().getWithStreams(channelId)!!.channel
        assertThat(stored.epgChannelId).isEqualTo("CCTV1.cn")
        assertThat(stored.epgMatch).isEqualTo(EpgMatchType.TVG_ID.name)

        val nowNext = repository.nowNext(channelId, now)!!
        assertThat(nowNext.now?.title).isEqualTo("正在播")
        assertThat(nowNext.next?.title).isEqualTo("接下来")
        assertThat(logger.codes).contains(EventCodes.EPG_COVERAGE)
        assertThat(logger.codes).contains(EventCodes.EPG_PARSE_OK)
        assertThat(logger.codes).contains(EventCodes.EPG_MATCH_HIT)
    }

    @Test
    fun `the coverage event carries the group dimension and the mainstream slice`() = runBlocking<Unit> {
        channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        useCase = buildUseCase(FakeProvider("test", body = guide(programme(now, now + 30 * 60_000L, "x"))))

        useCase()

        val event = logger.events.last { it.code == EventCodes.EPG_COVERAGE }
        assertThat(event.level).isEqualTo(LogLevel.INFO)
        assertThat(event.fields["byGroup"]).isEqualTo(mapOf("cctv" to 1))
        assertThat(event.fields["byGroupTotal"]).isEqualTo(mapOf("cctv" to 1))
        // EPG-BIND: the three readings, so "how many channels have an id" and "how many can show
        // something" are both on the event and neither has to be inferred from the other.
        assertThat(event.fields["matched"]).isEqualTo(1)
        assertThat(event.fields["withProgrammes"]).isEqualTo(1)
        assertThat(event.fields["emptyBinding"]).isEqualTo(0)
        assertThat(event.fields["ratio"]).isEqualTo("1.000")
        assertThat(event.fields["programmedRatio"]).isEqualTo("1.000")
        assertThat(event.fields["byGroupWithProgrammes"]).isEqualTo(mapOf("cctv" to 1))
        assertThat(event.fields["mainstreamMatched"]).isEqualTo(1)
        assertThat(event.fields["mainstreamWithProgrammes"]).isEqualTo(1)
        assertThat(event.fields["mainstreamEmptyBinding"]).isEqualTo(0)
        assertThat(event.fields["mainstreamTotal"]).isEqualTo(1)
        assertThat(event.fields["mainstreamRatio"]).isEqualTo("1.000")
        assertThat(event.fields["mainstreamProgrammedRatio"]).isEqualTo("1.000")
        assertThat(event.fields["target"]).isEqualTo("0.600")
        // Nothing to warn about: a fully covered mainstream list must not raise the alert field.
        assertThat(event.fields).doesNotContainKey("alert")
    }

    @Test
    fun `an empty binding is not counted in the mainstream reading the alert gates on`() = runBlocking<Unit> {
        // The channel is matched (its id is bound) but the guide publishes no <programme> for it: the
        // id-side number is a perfect 1/1, and the honest reading is 0/1. Before EPG-BIND the second
        // number did not exist, so the app reported 100% coverage over a blank grid.
        val channelId = channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        useCase = buildUseCase(FakeProvider("test", body = guide()))

        val report = (useCase() as AppResult.Ok).value

        assertThat(report.coverage.matched).isEqualTo(1)
        assertThat(report.coverage.withProgrammes).isEqualTo(0)
        assertThat(report.coverage.emptyBinding).isEqualTo(1)
        // The binding itself is kept — there was no better candidate, and keeping it is what lets the
        // next refresh (or a fold, or a manual pick) fill it in.
        assertThat(database.channelDao().getWithStreams(channelId)!!.channel.epgChannelId)
            .isEqualTo("CCTV1.cn")

        val event = logger.events.last { it.code == EventCodes.EPG_COVERAGE }
        assertThat(event.level).isEqualTo(LogLevel.WARN)
        assertThat(event.fields["alert"]).isEqualTo("coverage_below_target")
        assertThat(event.fields["mainstreamMatched"]).isEqualTo(1)
        assertThat(event.fields["mainstreamRatio"]).isEqualTo("1.000")
        assertThat(event.fields["mainstreamWithProgrammes"]).isEqualTo(0)
        assertThat(event.fields["mainstreamEmptyBinding"]).isEqualTo(1)
        assertThat(event.fields["mainstreamProgrammedRatio"]).isEqualTo("0.000")
    }

    @Test
    fun `the binding goes to the source whose guide id holds the most programmes`() = runBlocking<Unit> {
        // The EPG-TRAD-1 lesson, in miniature: both guides carry the channel, the deep one is offered
        // FIRST. The old loop let the later source overwrite it, so the pick was a property of the
        // source order rather than of the data.
        val channelId = channel("CCTV-1 综合", tvgId = null)
        useCase = buildUseCase(
            FakeProvider("a.deep", body = guideOf("deep.hk", "CCTV-1 综合", *slots("deep.hk", 3).toTypedArray())),
            FakeProvider("b.thin", body = guideOf("thin.cn", "CCTV-1 综合", *slots("thin.cn", 1).toTypedArray())),
        )

        val report = (useCase() as AppResult.Ok).value

        assertThat(database.channelDao().getWithStreams(channelId)!!.channel.epgChannelId).isEqualTo("deep.hk")
        assertThat(database.channelDao().getWithStreams(channelId)!!.channel.epgMatch)
            .isEqualTo(EpgMatchType.NAME_EXACT.name)
        assertThat(report.coverage.withProgrammes).isEqualTo(1)
        assertThat(report.coverage.emptyBinding).isEqualTo(0)

        // One DEBUG line per proposal, and each says how deep it is and whether it won.
        val deep = hitEvent("deep.hk")
        val thin = hitEvent("thin.cn")
        assertThat(deep.fields["programmesInWindow"]).isEqualTo(3)
        assertThat(deep.fields["chosen"]).isEqualTo(true)
        assertThat(thin.fields["programmesInWindow"]).isEqualTo(1)
        assertThat(thin.fields["chosen"]).isEqualTo(false)
    }

    @Test
    fun `the deeper source still wins when it is the later one`() = runBlocking<Unit> {
        // The other half of the rule: the pick must not become "first source wins" either. This is the
        // shape EPG-TRAD-1 measured (mainland id first and thin, Hong Kong id later and deep).
        val channelId = channel("CCTV-1 综合", tvgId = null)
        useCase = buildUseCase(
            FakeProvider("a.thin", body = guideOf("thin.cn", "CCTV-1 综合", *slots("thin.cn", 1).toTypedArray())),
            FakeProvider("b.deep", body = guideOf("deep.hk", "CCTV-1 综合", *slots("deep.hk", 8).toTypedArray())),
        )

        useCase()

        assertThat(database.channelDao().getWithStreams(channelId)!!.channel.epgChannelId).isEqualTo("deep.hk")
        assertThat(hitEvent("deep.hk").fields["chosen"]).isEqualTo(true)
        assertThat(hitEvent("thin.cn").fields["chosen"]).isEqualTo(false)
    }

    @Test
    fun `an empty binding loses to one that holds a single programme`() = runBlocking<Unit> {
        // 三沙卫视 vs 深圳卫视 of EPG-TRAD-1: the mainland id exists and publishes nothing, the Hong
        // Kong id publishes a few rows. Depth decides, and the losing proposal is still explained.
        val channelId = channel("CCTV-1 综合", tvgId = null)
        useCase = buildUseCase(
            FakeProvider("a.empty", body = guideOf("empty.cn", "CCTV-1 综合")),
            FakeProvider("b.few", body = guideOf("few.hk", "CCTV-1 综合", *slots("few.hk", 1).toTypedArray())),
        )

        val report = (useCase() as AppResult.Ok).value

        assertThat(database.channelDao().getWithStreams(channelId)!!.channel.epgChannelId).isEqualTo("few.hk")
        assertThat(report.coverage.emptyBinding).isEqualTo(0)
        assertThat(hitEvent("empty.cn").fields["programmesInWindow"]).isEqualTo(0)
        assertThat(hitEvent("empty.cn").fields["chosen"]).isEqualTo(false)
    }

    @Test
    fun `the depth is measured in the retention window, not over the whole guide`() = runBlocking<Unit> {
        // The window is the grid's own rule (`stop_ms >= from AND start_ms <= to`), so a programme that
        // straddles the left edge counts and one that starts after the right edge does not. The empty
        // source is proposed LAST and must still lose: depth outranks the source order.
        val channelId = channel("CCTV-1 综合", tvgId = null)
        val straddling = programme(
            now - 7 * 3_600_000L,
            now - 5 * 3_600_000L,
            "跨左边界",
            epgChannel = "straddle.cn",
        )
        val tooLate = programme(
            now + 72 * 3_600_000L,
            now + 73 * 3_600_000L,
            "太晚",
            epgChannel = "empty.cn",
        )
        useCase = buildUseCase(
            FakeProvider("a.straddle", body = guideOf("straddle.cn", "CCTV-1 综合", straddling)),
            FakeProvider("b.empty", body = guideOf("empty.cn", "CCTV-1 综合", tooLate)),
        )

        useCase()

        assertThat(hitEvent("straddle.cn").fields["programmesInWindow"]).isEqualTo(1)
        assertThat(hitEvent("empty.cn").fields["programmesInWindow"]).isEqualTo(0)
        assertThat(database.channelDao().getWithStreams(channelId)!!.channel.epgChannelId).isEqualTo("straddle.cn")
    }

    /** The `EPG_MATCH_HIT` DEBUG line for one proposed guide id. */
    private fun hitEvent(epgId: String) = logger.events
        .last { it.code == EventCodes.EPG_MATCH_HIT && it.fields["epgId"] == epgId }

    @Test
    fun `a mainstream list below the target raises the coverage alert as an event, not a notification`() =
        runBlocking<Unit> {
            // The list has a mainstream channel and the guide cannot cover it → 0/1 < 0.60. The alert is
            // the coverage event at WARN with a tag: docs/03 §3.3 has no `EPG_COVERAGE_LOW` code yet.
            channel("某个不存在的台", tvgId = "stale.id")
            useCase = buildUseCase(FakeProvider("test", body = guide(programme(now, now + 30 * 60_000L, "x"))))

            useCase()

            val event = logger.events.last { it.code == EventCodes.EPG_COVERAGE }
            assertThat(event.level).isEqualTo(LogLevel.WARN)
            assertThat(event.fields["alert"]).isEqualTo("coverage_below_target")
            assertThat(event.fields["mainstreamMatched"]).isEqualTo(0)
            assertThat(event.fields["mainstreamTotal"]).isEqualTo(1)
            assertThat(event.fields["mainstreamRatio"]).isEqualTo("0.000")
        }

    @Test
    fun `rows outside the retention window are counted as skipped and never stored`() = runBlocking<Unit> {
        val channelId = channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        useCase = buildUseCase(
            FakeProvider(
                id = "test",
                body = guide(
                    programme(now - 30 * 3_600_000L, now - 29 * 3_600_000L, "昨天"),
                    programme(now + 72 * 3_600_000L, now + 73 * 3_600_000L, "后天"),
                    programme(now, now + 30 * 60_000L, "现在"),
                ),
            ),
        )

        val report = (useCase() as AppResult.Ok).value

        assertThat(report.skipped).isEqualTo(2)
        assertThat(database.programmeDao().countForChannel("CCTV1.cn")).isEqualTo(1)
        assertThat(logger.codes).contains(EventCodes.EPG_COVERAGE)
    }

    @Test
    fun `a failing provider keeps the previously stored programmes`() = runBlocking<Unit> {
        val channelId = channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        useCase = buildUseCase(
            FakeProvider(
                id = "test",
                body = guide(programme(now, now + 30 * 60_000L, "旧的节目")),
            ),
        )
        useCase()

        val failing = FakeProvider(
            id = "test",
            body = null,
            failure = AppError.timeout(EventCodes.EPG_FETCH_FAIL),
        )
        useCase = buildUseCase(failing)
        val report = (useCase() as AppResult.Ok).value

        assertThat(report.providers).isEqualTo(0)
        // §6.3: a failed fetch degrades — the old data stays and the UI keeps working.
        assertThat(database.programmeDao().countForChannel("CCTV1.cn")).isEqualTo(1)
        assertThat(repository.nowNext(channelId, now)!!.now?.title).isEqualTo("旧的节目")
    }

    @Test
    fun `the second provider is still tried when the first one fails`() = runBlocking<Unit> {
        channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        useCase = buildUseCase(
            FakeProvider("broken", body = null, failure = AppError.network(EventCodes.EPG_FETCH_FAIL)),
            FakeProvider("working", body = guide(programme(now, now + 30 * 60_000L, "备胎源"))),
        )

        val report = (useCase() as AppResult.Ok).value

        assertThat(report.providers).isEqualTo(1)
        assertThat(database.programmeDao().countForChannel("CCTV1.cn")).isEqualTo(1)
        assertThat(logger.codes).contains(EventCodes.EPG_COVERAGE)
    }

    @Test
    fun `the enabled rows of epg_source decide which providers run`() = runBlocking<Unit> {
        channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        database.epgSourceDao().upsertAll(
            listOf(
                EpgSourceEntity("kept", "kept", "https://example.invalid/kept.xml", 1, null, null),
                EpgSourceEntity("dropped", "dropped", "https://example.invalid/dropped.xml", 1, null, null),
            ),
        )
        useCase = buildUseCase(
            FakeProvider("kept", body = guide(programme(now, now + 30 * 60_000L, "kept"))),
            FakeProvider("dropped", body = guide(programme(now, now + 30 * 60_000L, "dropped"))),
        )
        val both = (useCase() as AppResult.Ok).value
        assertThat(both.providers).isEqualTo(2)

        // Disabling a row in the settings store is what stops the refresh from hitting that endpoint.
        database.epgSourceDao().upsert(
            database.epgSourceDao().all().first { it.id == "kept" }.copy(enabled = 0),
        )
        val one = (useCase() as AppResult.Ok).value

        assertThat(one.providers).isEqualTo(1)
        assertThat(database.epgSourceDao().all().first { it.id == "dropped" }.lastResult).isNotNull()
        assertThat(logger.codes).contains(EventCodes.EPG_COVERAGE)
    }

    @Test
    fun `a channel with a manual binding keeps it and still counts as covered`() = runBlocking<Unit> {
        val manualId = channel("CCTV-1 综合", tvgId = null, epgChannelId = "user.pick")
        useCase = buildUseCase(
            FakeProvider("test", body = guide(programme(now, now + 30 * 60_000L, "x"))),
        )

        val report = (useCase() as AppResult.Ok).value

        assertThat(report.coverage.matched).isEqualTo(1)
        val stored = database.channelDao().getWithStreams(manualId)!!.channel
        assertThat(stored.epgChannelId).isEqualTo("user.pick")
        assertThat(stored.epgMatch).isEqualTo(EpgMatchType.MANUAL.name)
        assertThat(logger.codes).doesNotContain(EventCodes.EPG_MATCH_HIT)
    }

    @Test
    fun `a malformed guide is still read up to the break and reported`() = runBlocking<Unit> {
        channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        val truncated = "<tv><channel id=\"CCTV1.cn\"><display-name>CCTV-1 综合</display-name></channel>" +
            programme(now, now + 30 * 60_000L, "好的") +
            "<programme start=\"20260101000000 +0000\" stop=\"20260101010000 +0000\" channel=\"CCTV1.cn\"><title>断"
        useCase = buildUseCase(FakeProvider("test", body = truncated))

        val report = (useCase() as AppResult.Ok).value

        assertThat(report.programmes).isEqualTo(1)
        assertThat(database.programmeDao().countForChannel("CCTV1.cn")).isEqualTo(1)
    }

    @Test
    fun `the report's uncovered count is honest when nothing matches`() = runBlocking<Unit> {
        channel("不存在的台", tvgId = "nowhere.id")
        useCase = buildUseCase(
            FakeProvider("test", body = guide(programme(now, now + 30 * 60_000L, "x"))),
        )

        val report = (useCase() as AppResult.Ok).value

        assertThat(report.coverage.matched).isEqualTo(0)
        assertThat(report.coverage.total).isEqualTo(1)
        assertThat(logger.codes).contains(EventCodes.EPG_MATCH_MISS)
        // Rows that match nothing are still stored: they are the guide's data, and the next refresh may
        // match them once the channel list changes.
        assertThat(database.programmeDao().countForChannel("CCTV1.cn")).isEqualTo(1)
    }

    @Test
    fun `persisted channels keep their user columns across a refresh`() = runBlocking<Unit> {
        val channelId = channel("CCTV-1 综合", tvgId = "CCTV1.cn", epgChannelId = "user.pick")
        val entity = PersistenceMapper.toDomain(database.channelDao().getWithStreams(channelId)!!.channel)
        assertThat(entity.epgMatch).isEqualTo(EpgMatchType.MANUAL)

        useCase = buildUseCase(
            FakeProvider("test", body = guide(programme(now, now + 30 * 60_000L, "x"))),
        )
        useCase()

        assertThat(database.channelDao().getWithStreams(channelId)!!.channel.epgChannelId)
            .isEqualTo("user.pick")
    }

    @Test
    fun `a configured source with no registered provider is reported, not ignored`() = runBlocking<Unit> {
        channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        database.epgSourceDao().upsert(
            EpgSourceEntity("ghost.source", "ghost", "https://example.invalid/ghost.xml", 1, null, null),
        )
        // No provider registered for that row: the refresh must say so instead of silently skipping it.
        useCase = buildUseCase()

        val report = (useCase() as AppResult.Ok).value

        assertThat(report.providers).isEqualTo(0)
        assertThat(logger.codes).contains(EventCodes.EPG_FETCH_FAIL)
        assertThat(database.epgSourceDao().all().first { it.id == "ghost.source" }.lastResult)
            .isEqualTo("NO_PROVIDER")
    }

    @Test
    fun `a session that starts playing stops the remaining sources but keeps what already landed`() =
        runBlocking<Unit> {
            // P3-6, the run-time half of R7: the guide being fetched finishes, the next one is not
            // started. The first provider flips the process-wide signal as it returns.
            val channelId = channel("CCTV-1 综合", tvgId = "CCTV1.cn")
            var playing = false
            val first = FakeProvider(
                id = "first",
                body = guide(programme(now, now + 30 * 60_000L, "第一个源")),
                onFetch = { playing = true },
            )
            val second = FakeProvider("second", body = guide(programme(now, now + 30 * 60_000L, "第二个源")))
            useCase = buildUseCase(first, second, playback = PlaybackPrioritySignal { playing })

            val report = (useCase() as AppResult.Ok).value

            assertThat(report.providers).isEqualTo(1)
            assertThat(report.interrupted).isEqualTo("playback_priority")
            assertThat(first.fetches).isEqualTo(1)
            assertThat(second.fetches).isEqualTo(0)
            // What the one source wrote is kept (docs/02 §6.3: a partial run degrades, it does not roll back).
            assertThat(database.programmeDao().countForChannel("CCTV1.cn")).isEqualTo(1)
            assertThat(repository.nowNext(channelId, now)!!.now?.title).isEqualTo("第一个源")
            val event = logger.events.last { it.code == EventCodes.EPG_COVERAGE }
            assertThat(event.fields["interrupted"]).isEqualTo("playback_priority")
            assertThat(event.fields["providers"]).isEqualTo(1)
        }

    @Test
    fun `with avoidance off both sources run even while playing`() = runBlocking<Unit> {
        channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        val second = FakeProvider("second", body = guide(programme(now, now + 30 * 60_000L, "第二个源")))
        useCase = buildUseCase(
            FakeProvider("first", body = guide(programme(now, now + 30 * 60_000L, "第一个源"))),
            second,
            playback = PlaybackPrioritySignal { true },
        )

        val report = (useCase(respectPlayback = false) as AppResult.Ok).value

        assertThat(report.providers).isEqualTo(2)
        assertThat(report.interrupted).isNull()
        assertThat(second.fetches).isEqualTo(1)
    }

    @Test
    fun `re-running the same guide updates the same slots instead of adding rows`() = runBlocking<Unit> {
        // P3-6's idempotency promise at the storage level: §5.1's UNIQUE(epg_channel_id, start_ms) plus
        // INSERT OR REPLACE, so a second trigger over the same slot is an update, not a duplicate.
        val channelId = channel("CCTV-1 综合", tvgId = "CCTV1.cn")
        useCase = buildUseCase(
            FakeProvider("test", body = guide(programme(now, now + 30 * 60_000L, "第一版"))),
        )
        val firstRun = (useCase() as AppResult.Ok).value
        val firstCount = database.programmeDao().countForChannel("CCTV1.cn")

        useCase = buildUseCase(
            FakeProvider("test", body = guide(programme(now, now + 30 * 60_000L, "第二版"))),
        )
        val secondRun = (useCase() as AppResult.Ok).value

        assertThat(firstCount).isEqualTo(1)
        assertThat(database.programmeDao().countForChannel("CCTV1.cn")).isEqualTo(1)
        assertThat(secondRun.programmes).isEqualTo(firstRun.programmes)
        // Same slot, new content: the row was replaced, so now/next shows the newer title.
        assertThat(repository.nowNext(channelId, now)!!.now?.title).isEqualTo("第二版")
    }
}
