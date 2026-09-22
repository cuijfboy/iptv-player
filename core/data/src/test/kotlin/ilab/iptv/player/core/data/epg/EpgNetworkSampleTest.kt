package ilab.iptv.player.core.data.epg

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.data.catalog.AssetBundledPlaylist
import ilab.iptv.player.core.data.catalog.ChannelCatalog
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.data.store.RoomCatalogWriter
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.database.ProgrammeWindows
import ilab.iptv.player.core.domain.channel.ChannelGrouping
import ilab.iptv.player.core.epg.BuiltInEpgSources
import ilab.iptv.player.core.epg.EpgAliases
import ilab.iptv.player.core.epg.EpgCoverageCalculator
import ilab.iptv.player.core.epg.EpgMatcher
import ilab.iptv.player.core.epg.EpgProvider
import ilab.iptv.player.core.epg.XmltvHttpEpgProvider
import ilab.iptv.player.core.network.HttpRetryPolicy
import ilab.iptv.player.core.network.OkHttpFetcher
import ilab.iptv.player.core.source.normalize.Keys
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The **real-network** coverage sample, gated behind a Gradle property so `check` and CI stay
 * hermetic: `./gradlew :core:data:testDebugUnitTest -Piptv.epgSample=1` (the same shape as
 * `:core:source`'s `-Piptv.netSample=1` deep-probe sample).
 *
 * It is not a correctness test — the committed unit tests own that. It answers the two questions a
 * fake cannot: **does the pipeline survive a real, multi-megabyte guide**, and **what coverage does
 * the three-tier chain actually reach on the shipped 658-channel fixture**. Both answers are recorded
 * in `docs/05-过程记录/27-P2-7EPG验证.md`.
 *
 * The numbers printed here are the evidence. No guide content is written to the repository.
 */
@RunWith(AndroidJUnit4::class)
class EpgNetworkSampleTest {

    private class PrintingLogger : Logger {
        val codes = mutableListOf<String>()
        /** Same events, with fields, so the sample can turn the DEBUG match log into a miss report. */
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()

        private fun record(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) {
            codes += code
            events += code to fields
            if (code != "EPG_MATCH_HIT" && code != "EPG_MATCH_MISS") {
                println("  [$code] $message $fields")
            }
        }

        override fun log(event: LogEvent) = record(event.category, event.code, event.message, event.fields)
        override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            record(category, code, message, fields)

        override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            record(category, code, message, fields)

        override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            record(category, code, message, fields)

        override fun w(
            category: LogCategory,
            code: String,
            message: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) = record(category, code, message, fields)

        override fun e(
            category: LogCategory,
            code: String,
            message: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) = record(category, code, message, fields)

        override fun flush(timeoutMs: Long) = Unit
    }

    @Test
    fun `real guides are fetched, streamed and matched against the shipped fixture`() = runBlocking<Unit> {
        if (System.getProperty("iptv.epgSample").isNullOrBlank()) {
            println("EpgNetworkSampleTest skipped: pass -Piptv.epgSample=1 to run it")
            return@runBlocking
        }

        val logger = PrintingLogger()
        val database: IptvDatabase = RoomFixtures.inMemoryDatabase()
        // The real clock, unlike the unit tests: the retention window is `[now-6h, now+48h]`, so a
        // frozen "now" from 2023 would (correctly) skip every row of a guide published today and the
        // sample would measure nothing.
        val clock: ilab.iptv.player.core.common.Clock = ilab.iptv.player.core.log.AndroidClock()

        // 1. The shipped 658-channel fixture into Room — the same path the app boots through.
        val writer = RoomCatalogWriter(database, database.channelDao(), database.streamDao(), logger)
        val catalog = ChannelCatalog(writer, clock)
        val loaded = catalog.load(AssetBundledPlaylist(RoomFixtures.context().assets).read(), "p1-2-fixture")
        val load = (loaded as ilab.iptv.player.core.common.AppResult.Ok).value
        println("fixture: channels=${load.channels} streams=${load.streams} groups=${load.groups}")

        // 2. The real providers, over the real transport (no byte cap: the body streams).
        val fetcher = OkHttpFetcher(
            OkHttpClient(),
            logger,
            HttpRetryPolicy(maxAttempts = 2, initialBackoffMs = 500, maxBackoffMs = 1_000),
        )
        val providers: Set<EpgProvider> = BuiltInEpgSources.DEFAULT_CATALOG.map { descriptor ->
            XmltvHttpEpgProvider(
                id = descriptor.id,
                label = descriptor.label,
                url = descriptor.url,
                fetcher = fetcher,
                logger = logger,
            timeoutMs = 60_000,
            )
        }.toSet()

        // 3. The pipeline.
        val repository = RoomEpgRepository(database.channelDao(), database.programmeDao(), clock)
        // P3-4: capture what one run publishes to the manual-binding picker.
        val guideCatalog = RoomFixtures.InMemoryEpgChannelCatalog()
        val useCase = LoadEpgUseCase(
            providers = providers,
            repository = repository,
            channelCatalog = guideCatalog,
            channelDao = database.channelDao(),
            epgSourceDao = database.epgSourceDao(),
            programmeDao = database.programmeDao(),
            matcher = EpgMatcher(nameKey = Keys::nameKey, aliases = EpgAliases.BUILT_IN),
            clock = clock,
            logger = logger,
            dispatchers = TestDispatcherProvider(),
        )

        val runtime = Runtime.getRuntime()
        val usedBeforeBytes = runtime.totalMemory() - runtime.freeMemory()
        val startedAt = System.currentTimeMillis()
        val result = useCase()
        val wallMs = System.currentTimeMillis() - startedAt
        val usedAfterBytes = runtime.totalMemory() - runtime.freeMemory()
        val report = (result as ilab.iptv.player.core.common.AppResult.Ok).value

        val usedMb = { bytes: Long -> "%.1f".format(java.util.Locale.US, bytes / 1024.0 / 1024.0) }
        println("heap: maxMb=${runtime.maxMemory() / 1024 / 1024} usedBeforeMb=${usedMb(usedBeforeBytes)} usedAfterMb=${usedMb(usedAfterBytes)}")
        println("report: providers=${report.providers} programmes=${report.programmes} skipped=${report.skipped}")
        println(
            "coverage: matched=${report.coverage.matched} total=${report.coverage.total} " +
                "ratio=${report.coverage.ratio} byGroup=${report.coverage.byGroup}",
        )
        // EPG-BIND: the three readings of one run. `matched` is the id side (unchanged since P3-5),
        // `withProgrammes` is what a viewer can actually open, and `emptyBinding` is the difference —
        // the 三沙卫视 state that used to be reported as coverage.
        println(
            "coverage-verdicts: matched=${report.coverage.matched} " +
                "withProgrammes=${report.coverage.withProgrammes} " +
                "emptyBinding=${report.coverage.emptyBinding} total=${report.coverage.total} " +
                "ratio=${report.coverage.ratio} programmedRatio=${report.coverage.programmedRatio}",
        )
        val mainstream = EpgCoverageCalculator.mainstream(report.coverage)
        println(
            "coverage-by-group: " + report.coverage.byGroupTotal.entries.joinToString(" ") { (group, total) ->
                "${group.key}=${report.coverage.byGroup[group] ?: 0}/$total"
            },
        )
        println(
            "coverage-mainstream: matched=${mainstream.matched} " +
                "withProgrammes=${mainstream.withProgrammes} emptyBinding=${mainstream.emptyBinding} " +
                "total=${mainstream.total} ratio=${mainstream.ratio} " +
                "programmedRatio=${mainstream.programmedRatio} (docs/04 P3-5 target = 0.60)",
        )
        println("coverage-uncovered: " + uncoveredSummary(database))
        println("coverage-empty-binding: " + emptyBindingSummary(database, clock))
        println("coverage-competition: " + competitionSummary(database, logger))
        println("coverage-spotlight: " + spotlight(database))
        println("coverage-hit-tiers: " + tierSummary(logger))
        println("elapsedReportedMs=${report.elapsedMs} wallMs=$wallMs")
        println("programmeRows=${database.programmeDao().count()} epgChannels=${database.programmeDao().channelCount()}")
        println(
            "events: fetchOk=${logger.codes.count { it == "EPG_FETCH_OK" }} " +
                "fetchFail=${logger.codes.count { it == "EPG_FETCH_FAIL" }} " +
                "parseOk=${logger.codes.count { it == "EPG_PARSE_OK" }} " +
                "matchHit=${logger.codes.count { it == "EPG_MATCH_HIT" }} " +
                "matchMiss=${logger.codes.count { it == "EPG_MATCH_MISS" }} " +
                "coverage=${logger.codes.count { it == "EPG_COVERAGE" }}",
        )
        // P3-4: what the manual-binding picker would show, and whether the three documented gaps exist
        // in the guide at all (the "该 guide 无此频道" demo).
        println("p3-4-guide-catalog: entries=${guideCatalog.written.size}")
        listOf("TVB星河频道", "澳视澳门", "中天新闻").forEach { gap ->
            val hits = guideCatalog.written.filter {
                it.displayName.contains(gap, ignoreCase = true) || it.id.contains(gap, ignoreCase = true)
            }
            println("p3-4-gap: '$gap' -> hits=${hits.size} ${hits.map { it.id }}")
        }

        database.close()
        // The sample asserts only that the run happened and produced something; the numbers are the point.
        assertThat(report.coverage.total).isEqualTo(load.channels)
        assertThat(LogLevel.DEBUG).isNotNull()
    }

    /**
     * The channels that *have* a guide id but whose id holds nothing in the retention window — the
     * "covered on paper, blank in the app" set EPG-BIND reports as `emptyBinding`. These are the rows
     * the id-side coverage counted as a success, so the list is the evidence for the口径 change.
     */
    private suspend fun emptyBindingSummary(
        database: IptvDatabase,
        clock: ilab.iptv.player.core.common.Clock,
    ): String {
        val window = ProgrammeWindows.around(clock.nowMs())
        val counts = database.programmeDao().countByChannelInWindow(window.fromMs, window.toMs)
            .associate { it.epgChannelId to it.count }
        val empty = database.channelDao().all()
            .filter { !it.epgChannelId.isNullOrBlank() && (counts[it.epgChannelId] ?: 0) == 0 }
        val byGroup = empty.groupBy { ChannelGrouping.classify(it.groupTitle).key }
        return "count=${empty.size} " + byGroup.entries
            .sortedByDescending { it.value.size }
            .joinToString(" | ") { (group, rows) ->
                // Every name, not a sample: this list is the follow-up work list (a manual P3-4
                // binding needs the id it would replace), so the name carries the guide id it is bound to.
                "$group:${rows.size} [" +
                    rows.joinToString(",") { "${it.name}=${it.epgChannelId}" } + "]"
            }
    }

    /**
     * The channels more than one source proposed a binding for — the ones EPG-BIND actually decides.
     * Each proposal is printed as `guideId(rowsInWindow,win|lose)`, so the winner's margin is visible
     * and "the later source won" can be told from "the deeper guide won".
     */
    private suspend fun competitionSummary(database: IptvDatabase, logger: PrintingLogger): String {
        val names = database.channelDao().all().associate { it.id to it.name }
        return logger.events
            .filter { (code, _) -> code == "EPG_MATCH_HIT" }
            .groupBy { (_, fields) -> fields["channelId"] }
            .filterKeys { it != null }
            .values
            .filter { it.size > 1 }
            .joinToString(" | ") { proposals ->
                val channelId = proposals.first().second["channelId"]
                val name = names[channelId] ?: "id=$channelId"
                val detail = proposals.joinToString(",") { (_, fields) ->
                    val verdict = if (fields["chosen"] == true) "win" else "lose"
                    "${fields["epgId"]}(${fields["programmesInWindow"]},$verdict)"
                }
                "$name=[$detail]"
            }
            .ifEmpty { "(none)" }
    }

    /**
     * The channels that are *still* without a guide after every source has been tried — not the
     * per-source `EPG_MATCH_MISS` count, which includes every channel that merely lost to an earlier
     * source's spelling. This is the list an alias-table entry is supposed to come from (P3-5
     * maintenance rule 1), grouped the way the coverage report groups them, biggest group first.
     */
    private suspend fun uncoveredSummary(database: IptvDatabase): String {
        val uncovered = database.channelDao().all()
            .filter { it.epgChannelId.isNullOrBlank() }
            .map { ChannelGrouping.classify(it.groupTitle) to it.name }
        return uncovered.groupBy({ it.first }, { it.second })
            .entries
            .sortedByDescending { it.value.size }
            .joinToString(" | ") { (group, names) ->
                "${group.key}:${names.size} top=[${names.take(8).joinToString(",")}]"
            }
    }

    /**
     * The channels a script fold touches, one by one: which guide id the channel ended up bound to,
     * through which tier, and how many programmes that id actually holds. Three of these are the gaps
     * P3-5 could not close (`TVB星河频道`, `澳视澳门`, `中天新闻`); the others show what the fold
     * changes — not *whether* a channel is covered but **which guide serves it**, because the last
     * source that matches a channel owns its binding, and the Hong Kong guide is far deeper than the
     * mainland one for the Hong Kong/Shenzhen channels it carries (a channel bound to an id with no
     * programmes is covered on paper and blank in the app).
     */
    private suspend fun spotlight(database: IptvDatabase): String {
        val names = listOf(
            "凤凰卫视中文台", "凤凰卫视资讯台", "翡翠台", "明珠台",
            "TVB星河频道", "澳视澳门", "中天新闻",
            "三沙卫视", "深圳卫视", "深圳卫视 高清",
        )
        val channels = database.channelDao().all().associateBy { it.name }
        val parts = ArrayList<String>(names.size)
        for (name in names) {
            val row = channels[name]
            if (row == null) {
                parts += "$name=(absent)"
                continue
            }
            val epgChannelId = row.epgChannelId
            val rows = if (epgChannelId.isNullOrBlank()) 0 else database.programmeDao().countForChannel(epgChannelId)
            parts += "$name=${epgChannelId ?: "null"}:${row.epgMatch}:rows=$rows"
        }
        return parts.joinToString(" | ")
    }

    /** How many channels each match tier bound, per source attempt — the "why did it match" summary. */
    private fun tierSummary(logger: PrintingLogger): String {
        val tiers = logger.events
            .filter { (code, _) -> code == "EPG_MATCH_HIT" }
            .mapNotNull { (_, fields) -> fields["strategy"] as? String }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(" ") { "${it.key}=${it.value}" }
        return tiers.ifEmpty { "(none)" }
    }

    /**
     * The "几十 MB" claim, exceeded on purpose: `-Piptv.epgSample=huge` streams a ~195 MB gzip guide
     * (epgshare01's all-sources rip) and parses it with nothing but a heap measurement around it.
     *
     * It never touches Room — the point is the reader, not the store — and it reports the heap delta so
     * "不整文件入内存" is a number rather than an adjective.
     */
    @Test
    fun `a guide far larger than the heap streams through without buffering`() = runBlocking<Unit> {
        if (System.getProperty("iptv.epgSample") != "huge") {
            println("huge-guide sample skipped: pass -Piptv.epgSample=huge to run it")
            return@runBlocking
        }

        val logger = PrintingLogger()
        val fetcher = OkHttpFetcher(
            OkHttpClient(),
            logger,
            HttpRetryPolicy(maxAttempts = 2, initialBackoffMs = 500, maxBackoffMs = 1_000),
        )
        val url = "https://epgshare01.online/epgshare01/epg_ripper_ALL_SOURCES1.xml.gz"
        val startedAt = System.currentTimeMillis()
        val fetched = ilab.iptv.player.core.epg.XmltvHttpEpgProvider(
            id = "epgshare01.all",
            label = "epgshare01 (all sources)",
            url = url,
            fetcher = fetcher,
            logger = logger,
            timeoutMs = 600_000,
        ).fetch(ilab.iptv.player.core.log.AndroidClock())

        val runtime = Runtime.getRuntime()
        val before = runtime.totalMemory() - runtime.freeMemory()
        val stream = (fetched as ilab.iptv.player.core.common.AppResult.Ok).value
        var programmes = 0L
        var channels = 0L
        val parsed = ilab.iptv.player.core.epg.XmltvPullParser().parse(
            input = java.io.InputStreamReader(stream.body, Charsets.UTF_8),
            onChannel = { channels++ },
            onProgramme = { programmes++ },
        )
        // Collected first, so the number is *retained* memory and not "garbage the collector has not
        // got to yet" — the difference is the whole question of whether the guide was buffered.
        System.gc()
        val after = runtime.totalMemory() - runtime.freeMemory()
        stream.close()
        val wallMs = System.currentTimeMillis() - startedAt

        val usedMb = { bytes: Long -> "%.1f".format(java.util.Locale.US, bytes / 1024.0 / 1024.0) }
        println(
            "huge: declaredBytes=${stream.bytes} guideChannels=$channels programmes=$programmes " +
                "skipped=${parsed.skipped} malformed=${parsed.malformed}",
        )
        println("huge: maxHeapMb=${runtime.maxMemory() / 1024 / 1024} heapBeforeMb=${usedMb(before)} heapAfterMb=${usedMb(after)} wallMs=$wallMs")
        assertThat(programmes).isGreaterThan(1_000_000L)
    }
}
