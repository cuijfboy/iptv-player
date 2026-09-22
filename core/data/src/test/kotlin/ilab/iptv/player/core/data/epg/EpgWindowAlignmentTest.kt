package ilab.iptv.player.core.data.epg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.database.ProgrammeWindows
import ilab.iptv.player.core.database.entity.ChannelEntity
import ilab.iptv.player.core.database.entity.EpgSourceEntity
import ilab.iptv.player.core.epg.EpgAliases
import ilab.iptv.player.core.epg.EpgMatcher
import ilab.iptv.player.core.epg.EpgProvider
import ilab.iptv.player.core.epg.XmltvStream
import ilab.iptv.player.core.model.EpgGridWindow
import ilab.iptv.player.core.model.EpgWindowQuery
import ilab.iptv.player.core.model.Programme
import ilab.iptv.player.core.source.normalize.Keys
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **This is BUG-20260922-018's core assertion**: in one fixture, the coverage number the run reports
 * (`EPG_COVERAGE.withProgrammes`) equals the number of rows the grid can actually draw.
 *
 * The bug was a口径 split — the run counted "has programmes" over the retention window
 * `[now−6h, now+48h]` while the grid only draws the six hours it opens on, so a channel whose guide
 * published only *later* was reported as covered and rendered an empty row. Both producers of that
 * number (the run's report and the panel's `EpgRepository.coverage()`) now derive their window from
 * [EpgGridWindow], and the grid (`WindowPlanner`) derives its opening window from the same object; this
 * test pins the consequence: **the number and the picture are the same**.
 *
 * It is deliberately written against the two production read paths, not against the constant: the
 * fixture is small enough that the grid's page holds every channel, so the query below is the one
 * `EpgGridViewModel.load()` issues (window + channel page → `EpgWindowQuery`).
 */
@RunWith(AndroidJUnit4::class)
class EpgWindowAlignmentTest {

    private lateinit var database: IptvDatabase
    private lateinit var repository: RoomEpgRepository
    private lateinit var logger: RoomFixtures.RecordingLogger
    private lateinit var clock: Clock
    private lateinit var useCase: LoadEpgUseCase

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        database = RoomFixtures.inMemoryDatabase()
        clock = RoomFixtures.clock(now)
        repository = RoomEpgRepository(database.channelDao(), database.programmeDao(), clock)
        logger = RoomFixtures.RecordingLogger()
        runBlocking {
            database.epgSourceDao().upsert(
                EpgSourceEntity(
                    id = "test",
                    label = "test",
                    url = "https://example.invalid/test.xml",
                    enabled = 1,
                    lastFetchAt = null,
                    lastResult = null,
                ),
            )
        }
        useCase = LoadEpgUseCase(
            providers = setOf(FakeGuideProvider()),
            repository = repository,
            channelCatalog = RoomFixtures.InMemoryEpgChannelCatalog(),
            channelDao = database.channelDao(),
            epgSourceDao = database.epgSourceDao(),
            programmeDao = database.programmeDao(),
            matcher = EpgMatcher(nameKey = Keys::nameKey, aliases = EpgAliases.BUILT_IN),
            clock = clock,
            logger = logger,
            dispatchers = TestDispatcherProvider(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `the coverage count and the grid's renderable rows are one number`() = runBlocking<Unit> {
        val grid = EpgGridWindow.of(now)
        val retention = ProgrammeWindows.around(now)

        // Four channels, each with a manual binding so the fixture isolates the *window* question from
        // the matcher's decisions (EPG-BIND's tests own those).
        val onNow = channel("正在播")
        val later = channel("稍后")
        val far = channel("明晚")
        val blank = channel("空白")
        writeWindow(onNow, grid.fromMs - 30 * 60_000L, now + 30 * 60_000L)
        writeWindow(later, now + 4 * 3_600_000L, now + 5 * 3_600_000L)
        // Inside the retention window, outside the grid's: this is the row the bug counted as covered.
        writeWindow(far, now + 20 * 3_600_000L, now + 21 * 3_600_000L)

        // The old口径, in one number: everything the table keeps, whether or not the grid can draw it.
        val retainedWithProgrammes =
            database.channelDao().countWithEpgProgrammes(retention.fromMs, retention.toMs)
        // The grid's own read: the window it opens on, the channel page it asks for.
        val gridRows = renderableRows(grid.fromMs, grid.toMs)

        val report = (useCase() as AppResult.Ok).value
        val coverage = repository.coverage()

        // 3 vs 2 is the whole bug: three bindings hold something somewhere in the retained guide, and
        // only two of them draw a block in the grid the user opens.
        assertThat(retainedWithProgrammes).isEqualTo(3)
        assertThat(gridRows).isEqualTo(2)
        // ...and every口径 the app reports now agrees with the grid.
        assertThat(report.coverage.matched).isEqualTo(4)
        assertThat(report.coverage.withProgrammes).isEqualTo(gridRows)
        assertThat(report.coverage.emptyBinding).isEqualTo(2)
        assertThat(coverage.withProgrammes).isEqualTo(gridRows)
        assertThat(coverage.emptyBinding).isEqualTo(2)
        // The event a device log would show says the same thing (that is what QA read in VERIFY-1).
        val event = logger.events.last { it.code == EventCodes.EPG_COVERAGE }
        assertThat(event.fields["withProgrammes"]).isEqualTo(gridRows)
        assertThat(event.fields["emptyBinding"]).isEqualTo(2)
        // And the two empty ones are reported honestly: bound (matched) but blank in the window — the far
        // binding because its only programme is 20 h out, the blank one because its guide has nothing.
        assertThat(database.channelDao().getWithStreams(blank)!!.channel.epgChannelId).isEqualTo("空白.cn")
        assertThat(report.coverage.matched - report.coverage.withProgrammes).isEqualTo(2)
    }

    @Test
    fun `a programme that starts after the grid's right edge does not count as coverage`() =
        runBlocking<Unit> {
            val grid = EpgGridWindow.of(now)
            val justInside = channel("刚好在窗内")
            val justOutside = channel("刚好在窗外")
            // One minute either side of the right edge: the two counts must differ.
            writeWindow(justInside, grid.toMs - 60_000L, grid.toMs - 30_000L)
            writeWindow(justOutside, grid.toMs + 60_000L, grid.toMs + 90_000L)

            val report = (useCase() as AppResult.Ok).value

            assertThat(report.coverage.matched).isEqualTo(2)
            assertThat(report.coverage.withProgrammes).isEqualTo(1)
            assertThat(renderableRows(grid.fromMs, grid.toMs)).isEqualTo(1)
        }

    /** The channel page the grid asks for, in the grid's window — `EpgGridViewModel.load()`'s query. */
    private suspend fun renderableRows(fromMs: Long, toMs: Long): Int {
        val channels = database.channelDao().all()
        val idByEpgId = channels
            .filter { !it.epgChannelId.isNullOrBlank() }
            .associate { it.epgChannelId!! to it.id }
        val rows = repository.observeWindow(
            EpgWindowQuery(
                fromMs = fromMs,
                toMs = toMs,
                channelIds = channels.map { it.id },
            ),
        ).first()
        return rows.mapNotNull { idByEpgId[it.epgChannelId] }.distinct().size
    }

    private suspend fun channel(name: String): Long = database.channelDao().insert(
        ChannelEntity(
            id = 0,
            name = name,
            nameKey = Keys.nameKey(name),
            tvgId = null,
            groupKey = "cctv",
            groupTitle = "央视",
            logo = null,
            channelNo = null,
            favorite = false,
            hidden = false,
            sortOrder = 0,
            epgChannelId = null,
            epgMatch = "NONE",
            createdAt = now,
            updatedAt = now,
        ),
    ).let { id ->
        // The manual binding is what makes this fixture independent of the matcher: `setEpgBindings`
        // writes the same columns the P3-4 picker does.
        database.channelDao().setEpgBindings(
            listOf(ilab.iptv.player.core.database.dao.EpgBinding(id, "$name.cn", "MANUAL")),
            now,
        )
        id
    }

    /**
     * One programme on the channel's own guide id, written through the port the refresh uses. The guide
     * id is read back from SQL (the fixture's binding is `<name>.cn`), so the row cannot drift from the
     * binding the coverage count is keyed on.
     */
    private suspend fun writeWindow(channelId: Long, startMs: Long, stopMs: Long) {
        val epgChannelId = database.channelDao().getWithStreams(channelId)!!.channel.epgChannelId!!
        repository.replaceAll(
            epgChannelId,
            listOf(
                Programme(
                    id = 0,
                    epgChannelId = epgChannelId,
                    startMs = startMs,
                    stopMs = stopMs,
                    title = "P",
                    desc = null,
                    category = null,
                ),
            ),
        )
    }

    /**
     * A guide that matches nothing: this fixture is about windows, so no source should propose a
     * binding for the channels above.
     */
    private class FakeGuideProvider : EpgProvider {

        override val id: String = "test"
        override val label: String = "test"

        override suspend fun fetch(clock: Clock): AppResult<XmltvStream> {
            val body = (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n" +
                    "<channel id=\"unrelated.cn\"><display-name>某某台</display-name></channel>\n" +
                    "</tv>\n"
                ).toByteArray()
            return AppResult.Ok(XmltvStream(id, body.size.toLong(), ByteArrayInputStream(body)))
        }
    }
}
