package ilab.iptv.player.core.data.epg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.data.catalog.AssetBundledPlaylist
import ilab.iptv.player.core.data.catalog.ChannelCatalog
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.data.mapper.PersistenceMapper
import ilab.iptv.player.core.data.store.RoomCatalogWriter
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.database.entity.ChannelEntity
import ilab.iptv.player.core.domain.epg.EpgChannelHop
import ilab.iptv.player.core.epg.BuiltInEpgSources
import ilab.iptv.player.core.epg.EpgAliases
import ilab.iptv.player.core.epg.EpgMatcher
import ilab.iptv.player.core.epg.EpgProvider
import ilab.iptv.player.core.epg.XmltvHttpEpgProvider
import ilab.iptv.player.core.log.AndroidClock
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.EpgGridWindow
import ilab.iptv.player.core.model.EpgWindowQuery
import ilab.iptv.player.core.source.normalize.Keys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import ilab.iptv.player.core.network.HttpRetryPolicy
import ilab.iptv.player.core.network.OkHttpFetcher
import org.junit.Test
import org.junit.runner.RunWith

/**
 * BUG-20260922-018's reconciliation sample: **the grid's read path and the diagnostics panel's read
 * path, over one database, one fixture and one window, channel by channel.**
 *
 * The card's question is "why can the panel read programmes for a channel whose grid row is empty?".
 * The panel (`RoomEpgBindingReader`) counts `programme` rows per guide id and joins them to the channel
 * that owns the id; the grid (`EpgGridViewModel` → `RoomEpgRepository.observeWindow` →
 * `EpgChannelHop`) must arrive at the same answer by a different route. This sample runs both routes
 * on the shipped 658-channel fixture with today's real guides and prints every channel where they
 * disagree — the *diagnosis* half of the card, reproducible on this machine with no TV:
 *
 * ```
 * JAVA_HOME=…/jbr/Contents/Home ./gradlew --offline :core:data:testDebugUnitTest \
 *   -Piptv.epgSample=1 --tests "*EpgGridReconciliationSampleTest*" --rerun -i
 * ```
 *
 * It is gated like the other real-network sample so `check` and CI stay hermetic. The committed unit
 * tests (`EpgChannelHopTest`, `EpgGridViewModelTest`) own the correctness assertions; the numbers here
 * are the evidence that goes into `docs/05-过程记录/54-BUG018对账修复.md`.
 */
@RunWith(AndroidJUnit4::class)
class EpgGridReconciliationSampleTest {

    /** A logger that stays out of the way: this sample reads the database, not the match log. */
    private object QuietLogger : Logger {
        override fun log(event: LogEvent) = Unit
        override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) = Unit
        override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) = Unit
        override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) = Unit
        override fun w(
            category: LogCategory,
            code: String,
            message: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) = Unit

        override fun e(
            category: LogCategory,
            code: String,
            message: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) = Unit

        override fun flush(timeoutMs: Long) = Unit
    }

    @Test
    fun `the grid rows and the panel counts agree per channel on the real fixture`() = runBlocking<Unit> {
        if (System.getProperty("iptv.epgSample").isNullOrBlank()) {
            println("EpgGridReconciliationSampleTest skipped: pass -Piptv.epgSample=1 to run it")
            return@runBlocking
        }

        val database = RoomFixtures.inMemoryDatabase()
        val clock: Clock = AndroidClock()
        val writer = RoomCatalogWriter(database, database.channelDao(), database.streamDao(), QuietLogger)
        val load = (ChannelCatalog(writer, clock)
            .load(AssetBundledPlaylist(RoomFixtures.context().assets).read(), "p1-2-fixture") as AppResult.Ok).value
        println("fixture: channels=${load.channels} streams=${load.streams} groups=${load.groups}")

        val fetcher = OkHttpFetcher(
            OkHttpClient(),
            QuietLogger,
            HttpRetryPolicy(maxAttempts = 2, initialBackoffMs = 500, maxBackoffMs = 1_000),
        )
        val providers: Set<EpgProvider> = BuiltInEpgSources.DEFAULT_CATALOG.map { descriptor ->
            XmltvHttpEpgProvider(
                id = descriptor.id,
                label = descriptor.label,
                url = descriptor.url,
                fetcher = fetcher,
                logger = QuietLogger,
                timeoutMs = 60_000,
            )
        }.toSet()
        val repository = RoomEpgRepository(database.channelDao(), database.programmeDao(), clock)
        LoadEpgUseCase(
            providers = providers,
            repository = repository,
            channelCatalog = RoomFixtures.InMemoryEpgChannelCatalog(),
            channelDao = database.channelDao(),
            epgSourceDao = database.epgSourceDao(),
            programmeDao = database.programmeDao(),
            matcher = EpgMatcher(nameKey = Keys::nameKey, aliases = EpgAliases.BUILT_IN),
            clock = clock,
            logger = QuietLogger,
            dispatchers = TestDispatcherProvider(),
        )()

        val panel = RoomEpgBindingReader(database.channelDao(), database.programmeDao(), clock).bindingReport()
        println("window: from=${panel.window.fromMs} to=${panel.window.toMs}")
        println("panel: matched=${panel.matched} withProgrammes=${panel.withProgrammes} total=${panel.total}")
        println("duplicate-bindings: " + duplicateBindings(database.channelDao().all()))
        println("holder-pages: " + holderPages(database, clock))
        println("reconciliation: " + reconciliation(database, repository, clock, panel))
        println("spotlight-reconciliation: " + spotlight(database, repository, clock))

        database.close()
        assertThat(panel.total).isEqualTo(load.channels)
    }

    /**
     * The guide ids more than one business channel is bound to — the precondition of the collapsed
     * hop. Printed with the channel ids so a loss can be traced to the row that kept the programmes.
     */
    private fun duplicateBindings(channels: List<ChannelEntity>): String {
        val shared = channels
            .filter { !it.epgChannelId.isNullOrBlank() }
            .groupBy { it.epgChannelId!! }
            .filterValues { it.size > 1 }
            .entries
            .sortedByDescending { it.value.size }
        val rows = shared.sumOf { it.value.size }
        return "ids=${shared.size} rows=$rows " + shared.take(12).joinToString(" | ") { (id, holders) ->
            "$id:${holders.size}[" + holders.joinToString(",") { "${it.name}#${it.id}" } + "]"
        }
    }

    /**
     * Where each holder of the QA-named guide ids sits: grid row index, the 32-row page it belongs to,
     * and its position inside that page. The hop only collapses holders that share a page, so this is
     * what says *which* row of the two empties on a device and which one on this fixture — the loss
     * moves with the channel table (QA's 571-channel import is not this 658-channel fixture).
     */
    private suspend fun holderPages(database: IptvDatabase, clock: Clock): String {
        val gridChannels = database.channelDao().observeAll().first()
            .map { PersistenceMapper.toDomain(it).first }
            .filter { !it.hidden }
        val rowByChannel = gridChannels.withIndex().associate { (row, channel) -> channel.id to row }
        val spotlightIds = listOf("CCTV-1.hk", "545944", "561322")
        return spotlightIds.joinToString(" | ") { epgId ->
            val holders = gridChannels.filter { it.epgChannelId == epgId }
                .sortedBy { rowByChannel[it.id] }
            "$epgId=" + holders.joinToString(",") { channel ->
                val row = rowByChannel.getValue(channel.id)
                "${channel.name}#${channel.id}@row$row/page${row / PAGE_CHANNELS}[${row % PAGE_CHANNELS}]"
            }
        }
    }

    /**
     * One page at a time, exactly the way the grid asks: the visible page of ≤[PAGE_CHANNELS] channels
     * in grid order, its guide ids, one window query — then the hop back to business ids, once the way
     * the grid used to do it (`associate`, one-to-one) and once the way `EpgChannelHop` does it now.
     *
     * A **loss** is a channel the panel counted (`programmesInWindow > 0`) whose row gets no programme
     * from the hop: the panel says "9 programmes", the grid row is empty. That is BUG-018's shape, and
     * the target state is `lost=0` on both hops only after the fix — before it, `collapse` loses every
     * earlier holder of a shared guide id.
     */
    private suspend fun reconciliation(
        database: IptvDatabase,
        repository: RoomEpgRepository,
        clock: Clock,
        panel: ilab.iptv.player.core.model.EpgBindingReport,
    ): String {
        val window = EpgGridWindow.of(clock.nowMs())
        val gridChannels: List<Channel> = database.channelDao().observeAll().first()
            .map { PersistenceMapper.toDomain(it).first }
            .filter { !it.hidden }
        val panelByChannel = panel.rows.associateBy { it.channelId }
        var panelPositive = 0
        var collapseKept = 0
        var hopKept = 0
        val collapseLost = ArrayList<String>()
        val hopLost = ArrayList<String>()
        for (page in gridChannels.map { it.id }.chunked(PAGE_CHANNELS)) {
            val pageChannels = gridChannels.filter { it.id in page.toSet() }
            val programmes = repository.observeWindow(EpgWindowQuery(window.fromMs, window.toMs, page, 64)).first()
            val collapsed = collapseProgrammes(pageChannels, programmes)
            val hopped = EpgChannelHop.programmesByChannel(page, gridChannels, programmes)
            for (channel in pageChannels) {
                if ((panelByChannel[channel.id]?.programmesInWindow ?: 0) <= 0) continue
                panelPositive++
                if (!collapsed[channel.id].isNullOrEmpty()) collapseKept++ else collapseLost += label(channel)
                if (!hopped[channel.id].isNullOrEmpty()) hopKept++ else hopLost += label(channel)
            }
        }
        return "panelPositiveRows=$panelPositive collapseKept=$collapseKept collapseLost=${collapseLost.size} " +
            "hopKept=$hopKept hopLost=${hopLost.size} " +
            "collapseLostRows=${collapseLost.take(12)} hopLostRows=${hopLost.take(12)}"
    }

    /** The four channels the QA round named on the device, read through both paths at one instant. */
    private suspend fun spotlight(database: IptvDatabase, repository: RoomEpgRepository, clock: Clock): String {
        val window = EpgGridWindow.of(clock.nowMs())
        val names = listOf("CCTV1", "CCTV-11戏曲", "CCTV-12社会与法", "CGTN俄语")
        val channels = database.channelDao().all().map { PersistenceMapper.toDomain(it) }
        val panel = RoomEpgBindingReader(database.channelDao(), database.programmeDao(), clock).bindingReport()
        val panelByChannel = panel.rows.associateBy { it.channelId }
        val lines = ArrayList<String>(names.size)
        for (name in names) {
            val channel = channels.filter { it.name == name }.minByOrNull { it.id }
            if (channel == null) {
                lines += "$name=(not in fixture)"
            } else {
                val programmes = repository
                    .observeWindow(EpgWindowQuery(window.fromMs, window.toMs, listOf(channel.id), 64))
                    .first()
                val hopped = EpgChannelHop.programmesByChannel(listOf(channel.id), channels, programmes)
                val row = panelByChannel[channel.id]
                lines += "$name#${channel.id}[${channel.channelNo}] epgId=${channel.epgChannelId} " +
                    "tier=${row?.matchedBy} panel=${row?.programmesInWindow} " +
                    "gridQuery=${programmes.size} hop=${hopped[channel.id]?.size ?: 0} " +
                    "sharers=${channels.count { it.epgChannelId == channel.epgChannelId }}"
            }
        }
        return lines.joinToString(" | ")
    }

    /** The pre-fix hop, kept in the sample so the evidence shows both readings of one page. */
    private fun collapseProgrammes(
        pageChannels: List<Channel>,
        programmes: List<ilab.iptv.player.core.model.Programme>,
    ): Map<Long, List<ilab.iptv.player.core.model.Programme>> {
        val channelIdByEpgId = pageChannels
            .filter { it.epgChannelId != null }
            .associate { it.epgChannelId!! to it.id }
        return programmes.groupBy { channelIdByEpgId[it.epgChannelId] }
            .mapNotNull { (channelId, rows) -> channelId?.let { it to rows } }
            .toMap()
    }

    private fun label(channel: Channel): String = "${channel.name}#${channel.id}(${channel.epgChannelId})"

    private companion object {
        /** `WindowPlanner.PAGE_CHANNELS`: the grid asks for one 32-row page (plus prefetch) at a time. */
        const val PAGE_CHANNELS = 32
    }
}
