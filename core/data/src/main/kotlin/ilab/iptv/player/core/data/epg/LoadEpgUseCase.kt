package ilab.iptv.player.core.data.epg

import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.DispatcherProvider
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.mapper.PersistenceMapper
import ilab.iptv.player.core.database.ProgrammeWindows
import ilab.iptv.player.core.database.dao.ChannelDao
import ilab.iptv.player.core.database.dao.EpgBinding
import ilab.iptv.player.core.database.dao.EpgSourceDao
import ilab.iptv.player.core.database.entity.EpgSourceEntity
import ilab.iptv.player.core.domain.repository.EpgRepository
import ilab.iptv.player.core.epg.EpgCoverageCalculator
import ilab.iptv.player.core.epg.EpgMatcher
import ilab.iptv.player.core.epg.EpgNameKey
import ilab.iptv.player.core.epg.EpgProvider
import ilab.iptv.player.core.epg.BuiltInEpgSources
import ilab.iptv.player.core.epg.EpgSourceRow
import ilab.iptv.player.core.epg.XmltvChannel
import ilab.iptv.player.core.epg.XmltvPullParser
import ilab.iptv.player.core.epg.epgChannelIndex
import ilab.iptv.player.core.model.EpgLoadReport
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.Programme
import java.io.IOException
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * The EPG refresh of docs/02 §6.3: `fetch → 流式解析 → 时间窗过滤 → ChannelMatcher 链 → Room 事务写入 →
 * now/next`.
 *
 * **Placement.** §4.3 sketches `LoadEpgUseCase` in `:core:domain`, but the orchestration needs both
 * `EpgProvider` (in `:core:epg`, §4.4) and `EpgRepository` (`:core:domain`), and the §3.2 matrix
 * gives `:core:domain` no `epg` column. The module that can see both is `:core:data` — the same
 * ruling that put P1-5's fail-over coordinator in `:feature:player` (docs/02 §3.2 "协调=feature").
 * The frozen *port* keeps its documented home; only the wiring moves.
 *
 * **Streaming.** A guide is tens of megabytes, so nothing here holds one: the parser hands rows out
 * one at a time ([XmltvPullParser]'s sinks are `suspend`, which is what makes the write the
 * back-pressure point), rows outside the retention window are counted and dropped, and the rest leave
 * in `INSERT OR REPLACE` batches of [WRITE_BATCH] — one transaction per batch (docs/02 §4.5 C4).
 *
 * **Degradation** (§6.3): a provider that fails is logged, skipped, and the previously stored
 * programmes stay. A refresh that matched nothing does not delete what it had.
 */
@Singleton
class LoadEpgUseCase @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards EpgProvider>,
    private val repository: EpgRepository,
    private val channelDao: ChannelDao,
    private val epgSourceDao: EpgSourceDao,
    private val matcher: EpgMatcher,
    private val clock: Clock,
    private val logger: Logger,
    private val dispatchers: DispatcherProvider,
    private val parser: XmltvPullParser = XmltvPullParser(),
) {

    suspend operator fun invoke(force: Boolean = false): AppResult<EpgLoadReport> {
        val startedAtMs = clock.nowMs()
        val window = ProgrammeWindows.around(startedAtMs)
        // The match and the coverage both read channels, and both want the domain shape (`group` is
        // derived, `epg_match` is an enum), so the rows are mapped once here (docs/02 §5.2).
        val channels = channelDao.all().map { PersistenceMapper.toDomain(it) }
        val sources = enabledSources()

        var programmes = 0
        var skipped = 0
        var channelsSeen = 0
        var providersRun = 0
        var malformed = false
        val bindings = LinkedHashMap<Long, EpgBinding>()
        val matchedIds = HashSet<Long>()

        for (source in sources) {
            val provider = providers.firstOrNull { it.id == source.id }
            if (provider == null) {
                // The table names a source this build has no provider for (a P2-6 edit, or a row from a
                // newer version). Report it instead of silently ignoring the row.
                recordResult(source.id, startedAtMs, "NO_PROVIDER")
                logger.w(
                    LogCategory.EPG,
                    EventCodes.EPG_FETCH_FAIL,
                    "epg source has no registered provider",
                    mapOf("provider" to source.id, "url" to source.url),
                )
                continue
            }

            val stream = when (val fetched = provider.fetch(clock)) {
                // `EPG_FETCH_FAIL` is already logged by the provider, with the failure class.
                is AppResult.Err -> {
                    recordResult(source.id, startedAtMs, "FAIL:${fetched.error.failure.name}")
                    continue
                }

                is AppResult.Ok -> fetched.value
            }
            providersRun++

            val indexChannels = ArrayList<XmltvChannel>(INITIAL_CHANNEL_INDEX)
            val pending = ArrayList<Programme>(WRITE_BATCH)
            var providerProgrammes = 0
            var providerSkipped = 0
            val parseStartedAtMs = clock.nowMs()

            suspend fun flush() {
                if (pending.isEmpty()) return
                // One batch can span channels (a guide interleaves them); the port is per EPG channel, so
                // the batch is grouped before it is written. Each group is its own transaction — the same
                // shape as `RoomCatalogWriter`'s batches, and still bounded by WRITE_BATCH rows.
                for ((epgChannelId, rows) in pending.groupBy { it.epgChannelId }) {
                    repository.replaceAll(epgChannelId, rows)
                }
                pending.clear()
            }

            val parseResult = try {
                withContext(dispatchers.default) {
                    InputStreamReader(stream.body, Charsets.UTF_8).use { reader ->
                        parser.parse(
                            input = reader,
                            onChannel = { indexChannels += it },
                            onProgramme = { row ->
                                providerProgrammes++
                                if (row.stopMs < window.fromMs || row.startMs > window.toMs) {
                                    // Outside [now-6h, now+48h]: storing it would only be deleted by the
                                    // prune at the end of this same run (§5.1).
                                    providerSkipped++
                                } else {
                                    pending += Programme(
                                        id = 0,
                                        epgChannelId = row.epgChannelId,
                                        startMs = row.startMs,
                                        stopMs = row.stopMs,
                                        title = row.title,
                                        desc = row.desc,
                                        category = row.category,
                                    )
                                    if (pending.size >= WRITE_BATCH) flush()
                                }
                            },
                        )
                    }
                }
            } catch (io: IOException) {
                // The body failed mid-stream. The rows written so far are valid rows; report the run as
                // partial and keep going (docs/02 §11: no half-deleted data, no crash).
                stream.close()
                recordResult(source.id, startedAtMs, "FAIL:${io.javaClass.simpleName}")
                logger.w(
                    LogCategory.EPG,
                    EventCodes.EPG_FETCH_FAIL,
                    "epg body failed mid-stream",
                    mapOf(
                        "provider" to source.id,
                        "programmes" to providerProgrammes,
                        "failure" to io.javaClass.simpleName,
                    ),
                    io,
                )
                malformed = true
                continue
            } finally {
                stream.close()
            }
            withContext(dispatchers.default) { flush() }

            programmes += providerProgrammes
            skipped += providerSkipped + parseResult.skipped
            channelsSeen += parseResult.channels
            malformed = malformed || parseResult.malformed
            recordResult(source.id, startedAtMs, "OK:$providerProgrammes")

            logger.d(
                LogCategory.EPG,
                EventCodes.EPG_PARSE_OK,
                "epg parsed",
                mapOf(
                    "provider" to source.id,
                    "channels" to parseResult.channels,
                    "programmes" to providerProgrammes,
                    "skipped" to (providerSkipped + parseResult.skipped),
                    "malformed" to parseResult.malformed,
                    "ms" to (clock.nowMs() - parseStartedAtMs),
                    "bytes" to stream.bytes,
                ),
            )

            // Match AFTER the parse: the index is only complete when every <channel> has been seen, and
            // a guide whose programmes precede its channels still matches correctly.
            val index = epgChannelIndex(indexChannels, EpgNameKey::key)
            val report = matcher.match(channels, index)
            val updatedAt = clock.nowMs()
            for (hit in report.hits) {
                bindings[hit.channelId] = EpgBinding(hit.channelId, hit.epgChannelId, hit.type.name)
                matchedIds += hit.channelId
                // DEBUG on purpose: one event per channel is exactly the "explainable" record the job
                // asks for (which tier, which field), and it is off in the shipping log level (docs/03 §3.3).
                logger.d(
                    LogCategory.EPG,
                    EventCodes.EPG_MATCH_HIT,
                    "epg channel matched",
                    mapOf(
                        "channelId" to hit.channelId,
                        "strategy" to hit.type.name,
                        "epgId" to hit.epgChannelId,
                        "matchedOn" to hit.matchedOn,
                        // The guide-side key of a name-based hit, so a folded match shows both sides
                        // (`matchedOn=cctv1高清` vs `guideKey=cctv1`) and the fold is auditable.
                        "guideKey" to hit.guideKey,
                        "provider" to source.id,
                    ),
                )
            }
            for (miss in report.misses) {
                logger.d(
                    LogCategory.EPG,
                    EventCodes.EPG_MATCH_MISS,
                    "epg channel unmatched",
                    mapOf(
                        "channelId" to miss.channelId,
                        "nameKey" to miss.nameKey,
                        "tvgId" to miss.triedTvgId,
                        "provider" to source.id,
                        "strategies" to MATCH_TIERS,
                    ),
                )
            }
            if (report.hits.isNotEmpty()) {
                val rows = report.hits.mapNotNull { bindings[it.channelId] }
                channelDao.setEpgBindings(rows, updatedAt)
            }

            // A manual binding never went through `match`, but it still counts as covered.
            matchedIds += channels
                .filter { it.epgMatch == EpgMatchType.MANUAL && !it.epgChannelId.isNullOrBlank() }
                .map { it.id }
        }

        val pruned = repository.prune(window.fromMs, window.toMs)
        val coverage = EpgCoverageCalculator.of(channels, matchedIds)
        val mainstream = EpgCoverageCalculator.mainstream(coverage)
        val elapsedMs = clock.nowMs() - startedAtMs

        val belowTarget = mainstream.total > 0 && mainstream.ratio < MAINSTREAM_TARGET
        val coverageFields = mapOf(
            "matched" to coverage.matched,
            "total" to coverage.total,
            "ratio" to ratioText(coverage.ratio),
            // P3-5: group dimension with both halves, so "42/80 央视" is readable straight from the
            // event instead of joining it against the catalogue.
            "byGroup" to coverage.byGroup.mapKeys { (group, _) -> group.key },
            "byGroupTotal" to coverage.byGroupTotal.mapKeys { (group, _) -> group.key },
            // docs/04's P3-5 exit is measured on 主流频道 (see MAINSTREAM_GROUPS); both ratios are
            // reported because docs/01–04 never define the word (a口径 question for god/arch).
            "mainstreamMatched" to mainstream.matched,
            "mainstreamTotal" to mainstream.total,
            "mainstreamRatio" to ratioText(mainstream.ratio),
            "target" to ratioText(MAINSTREAM_TARGET),
            "providers" to providersRun,
            "sources" to sources.size,
            "channels" to channelsSeen,
            "programmes" to programmes,
            "skipped" to skipped,
            "pruned" to pruned,
            "malformed" to malformed,
            "elapsedMs" to elapsedMs,
        )

        // The alert is a log event, not a notification (§6.3 "不打扰用户"): same registered code as the
        // plain report, raised to WARN and tagged, because docs/03 §3.3 is frozen at 50 codes and a new
        // `EPG_COVERAGE_LOW` would need a docs/03 write-back (proposed, see the P3-5 record §7).
        if (belowTarget) {
            logger.w(
                LogCategory.EPG,
                EventCodes.EPG_COVERAGE,
                "epg coverage below target",
                coverageFields + mapOf("alert" to COVERAGE_BELOW_TARGET),
            )
        } else {
            logger.i(LogCategory.EPG, EventCodes.EPG_COVERAGE, "epg coverage", coverageFields)
        }

        return AppResult.Ok(
            EpgLoadReport(
                providers = providersRun,
                channels = coverage.total,
                programmes = programmes,
                skipped = skipped,
                coverage = coverage,
                elapsedMs = elapsedMs,
            ),
        )
    }

    /**
     * The `epg_source` table is the configuration (docs/02 §5.1); [BuiltInEpgSources] is only the seed
     * for the first run. Reading it here — rather than injecting a fixed provider list — is what makes
     * "可在设置里配置" true without a settings screen: a row that is disabled or removed stops the
     * refresh from hitting that endpoint.
     */
    private suspend fun enabledSources(): List<EpgSourceEntity> {
        val stored = epgSourceDao.all()
        if (stored.isEmpty()) {
            epgSourceDao.upsertAll(BuiltInEpgSources.seedRows().map { it.toEntity() })
            return epgSourceDao.all().filter { it.enabled != 0 }
        }
        return stored.filter { it.enabled != 0 }
    }

    private suspend fun recordResult(sourceId: String, atMs: Long, result: String) {
        val existing = epgSourceDao.all().firstOrNull { it.id == sourceId } ?: return
        epgSourceDao.upsert(existing.copy(lastFetchAt = atMs, lastResult = result))
    }

    private companion object {
        /** §4.5 C4's batch size, reused so an EPG write has the same transaction shape as a catalog write. */
        const val WRITE_BATCH = 500

        const val INITIAL_CHANNEL_INDEX = 256

        /** Logged with every miss so the reader knows which tiers were tried, not just that none hit. */
        const val MATCH_TIERS = "TVG_ID,NAME_EXACT,NAME_FUZZY,ALIAS"

        /** docs/04 P3-5: "≥60%（主流频道）". The gate is on the mainstream slice, not the whole list. */
        const val MAINSTREAM_TARGET = 0.60

        const val COVERAGE_BELOW_TARGET = "coverage_below_target"

        fun ratioText(ratio: Double): String = String.format(java.util.Locale.US, "%.3f", ratio)
    }
}

/** `:core:data` is the only layer that can see both the seed (`:core:epg`) and the table (`:core:database`). */
private fun EpgSourceRow.toEntity(): EpgSourceEntity = EpgSourceEntity(
    id = id,
    label = label,
    url = url,
    enabled = if (enabled) 1 else 0,
    lastFetchAt = null,
    lastResult = null,
)
