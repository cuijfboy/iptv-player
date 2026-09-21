package ilab.iptv.player.core.data.source

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.DispatcherProvider
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.domain.repository.SourceRepository
import ilab.iptv.player.core.domain.source.SourceManagementPort
import ilab.iptv.player.core.model.RawEntry
import ilab.iptv.player.core.model.SourceConfig
import ilab.iptv.player.core.model.SourceKind
import ilab.iptv.player.core.network.HttpFetcher
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.provider.RemotePlaylistSourceProvider
import ilab.iptv.player.core.source.provider.SourceDescriptor
import ilab.iptv.player.core.source.provider.SourceProvider
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own subscription URLs as one [SourceProvider] (P2-6 正篇 item 2).
 *
 * **Why a composite provider instead of one provider per row.** `RefreshSourcesUseCase` takes its
 * `Set<SourceProvider>` from Hilt at construction time, so the set is a compile-time fact; a row the
 * user adds at runtime cannot join it. Rather than teach the P2-4 pipeline to ask a repository for
 * providers (the dispatch forbids changing pipeline code), this provider is registered once through
 * `@IntoSet` and fans out over the enabled rows when its `fetch` is called. The pipeline therefore
 * treats "my subscriptions" exactly like any other source: same Fetch stage, same budget, same
 * dedupe/normalize/scoring downstream, same `SRC_FETCH_*` events.
 *
 * Each row is fetched by a [RemotePlaylistSourceProvider] built from it, so the retrieval, the
 * charset fallback, the parse and their events are the shipped P2-4a code path — not a second
 * implementation that could drift.
 *
 * Rules this class owns:
 * - **enabled only.** A disabled row is never fetched (the enable switch is the user's cost control);
 * - **one bad row never kills the run.** A subscription that fails (404, timeout, unparseable body)
 *   contributes a `SRC_FETCH_FAIL` / `SRC_PARSE_FAIL` and is skipped; the other rows still return
 *   their entries — the same containment rule the Fetch stage applies to aggregates;
 * - **the row records its own result.** `last_fetch_at` / `last_result` / `entry_count` are written
 *   after each attempt, which is what the settings row's "最近结果" shows;
 * - **`channel.source_id` is the row id** (`sub:<hex>`), so a stream can be traced back to the
 *   subscription that produced it.
 */
@Singleton
class SubscriptionSourceProvider @Inject constructor(
    private val sources: SourceRepository,
    private val status: SourceManagementPort,
    private val fetcher: HttpFetcher,
    private val logger: Logger,
    private val limits: PipelineLimits,
    private val dispatchers: DispatcherProvider,
) : SourceProvider {

    override val id: String = ID
    override val label: String = LABEL
    override val kind: SourceKind = SourceKind.M3U

    override suspend fun fetch(clock: Clock): AppResult<List<RawEntry>> = withContext(dispatchers.io) {
        val rows = sources.all().filter { it.enabled && !it.builtIn }
        if (rows.isEmpty()) return@withContext AppResult.Ok(emptyList())

        val entries = ArrayList<RawEntry>()
        var failed = 0
        var firstFailure: AppError? = null
        for (row in rows) {
            // Cancellation must stay cancellation: only real failures are swallowed (docs/02 §4.0 F2).
            val result = try {
                providerFor(row).fetch(clock)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppResult.Err(AppError.unknown(EventCodes.SRC_FETCH_FAIL, e))
            }
            val atMs = clock.nowMs()
            when (result) {
                is AppResult.Ok -> {
                    entries += result.value
                    status.recordOutcome(row.id, atMs, ok = true, entryCount = result.value.size, detail = null)
                }

                is AppResult.Err -> {
                    failed++
                    if (firstFailure == null) firstFailure = result.error
                    status.recordOutcome(
                        row.id,
                        atMs,
                        ok = false,
                        entryCount = 0,
                        detail = result.error.failure.name,
                    )
                }
            }
        }

        // If every subscription failed there is nothing to contribute; reporting Ok(empty) would hide
        // that behind a successful source count. One failure among many is not reported here — the
        // per-row `SRC_FETCH_FAIL` already says which one, and the successful rows must still land.
        if (failed == rows.size) {
            AppResult.Err(firstFailure ?: AppError.unknown(EventCodes.SRC_FETCH_FAIL))
        } else {
            logger.d(
                LogCategory.SOURCE,
                EventCodes.SRC_DEDUPE,
                "subscriptions fetched",
                mapOf(
                    "provider" to ID,
                    "sources" to rows.size,
                    "failed" to failed,
                    "entries" to entries.size,
                ),
            )
            AppResult.Ok(entries)
        }
    }

    /** The P2-4a provider for one row. `kind` is a label; the parser still auto-detects the dialect. */
    private fun providerFor(row: SourceConfig): SourceProvider = RemotePlaylistSourceProvider(
        descriptor = SourceDescriptor(id = row.id, label = row.label, kind = row.kind, url = row.url),
        fetcher = fetcher,
        logger = logger,
        limits = limits,
    )

    companion object {
        /** Stable id of the composite provider; also the pipeline's `provider` field for its logs. */
        const val ID = "user.subscriptions"
        const val LABEL = "我的订阅"
    }
}
