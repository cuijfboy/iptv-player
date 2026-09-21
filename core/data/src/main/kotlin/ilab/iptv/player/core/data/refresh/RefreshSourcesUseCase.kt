package ilab.iptv.player.core.data.refresh

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.SessionIdFactory
import ilab.iptv.player.core.data.mapper.ChannelMapper
import ilab.iptv.player.core.data.mapper.MappedCatalog
import ilab.iptv.player.core.domain.repository.StreamRepository
import ilab.iptv.player.core.model.InterruptionReason
import ilab.iptv.player.core.model.ProbeContext
import ilab.iptv.player.core.model.RawEntry
import ilab.iptv.player.core.model.RefreshInterruption
import ilab.iptv.player.core.model.RefreshOptions
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.model.RefreshProgress
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamOutcome
import ilab.iptv.player.core.model.StreamTarget
import ilab.iptv.player.core.model.ValidationStage
import ilab.iptv.player.core.source.normalize.NormalizedPlaylist
import ilab.iptv.player.core.source.normalize.PlaylistNormalizer
import ilab.iptv.player.core.source.pipeline.ConcurrencyGovernor
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.pipeline.PlaybackPrioritySignal
import ilab.iptv.player.core.source.pipeline.RefreshBudget
import ilab.iptv.player.core.source.pipeline.StageProfiles
import ilab.iptv.player.core.source.provider.SourceProvider
import ilab.iptv.player.core.source.provider.StreamValidator
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/**
 * The front half of the docs/02 §6.1 refresh pipeline: **Fetch → Parse → Normalize → Dedupe → Cap →
 * Shallow → Persist**. Deep probe / score / select are P2-4b; this use case stops after the shallow
 * reachability verdict and persists it.
 *
 * Everything hard about F2/R6/R7 lives here:
 * - a **deadline-driven budget** ([RefreshBudget]); the run never starts work it cannot finish and
 *   instead stops admitting items and finishes gracefully with `BUDGET_EXCEEDED`;
 * - **incremental, breakpoint-resumable** progress: every shallow verdict is written immediately
 *   (`StreamRepository.recordOutcome`), and a re-run skips streams whose health is still fresh
 *   (24 h for healthy, 6 h for failed, docs/02 §6.1). A second run over an unchanged catalog
 *   therefore does no work and changes nothing — idempotent;
 * - **cancellation** — every suspend point is cancellable and `CancellationException` is rethrown
 *   (docs/02 §4.5 C5), so the caller collecting the [Flow] can stop a run by cancelling;
 * - **playback avoidance** (R7) — while a session plays and `respectPlayback` is on,
 *   [ConcurrencyGovernor] halves Fetch/Shallow concurrency.
 *
 * It consumes the two extension points as Hilt sets, so a new source or validator is a binding, not
 * a change here (docs/02 §9 E1/E2).
 */
class RefreshSourcesUseCase @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards SourceProvider>,
    private val validators: Set<@JvmSuppressWildcards StreamValidator>,
    private val streamRepository: StreamRepository,
    private val limits: PipelineLimits,
    private val clock: Clock,
    private val logger: Logger,
    private val sessionIds: SessionIdFactory,
    private val playback: PlaybackPrioritySignal,
) {

    /** Healthy streams are trusted for 24 h, failed ones re-probed after 6 h (docs/02 §6.1). */
    private val healthyTtlMs: Long = 24 * 60 * 60_000L
    private val failedTtlMs: Long = 6 * 60 * 60_000L

    operator fun invoke(options: RefreshOptions): Flow<RefreshProgress> = flow {
        val startedAt = clock.nowMs()
        val budget = RefreshBudget(startedAt + options.budgetMs, clock)
        val governor = ConcurrencyGovernor(limits, playback, options.respectPlayback)
        val session = sessionIds.newId("refresh")
        // ON_DEMAND_SINGLE_CHANNEL (re-probe one channel) is the DEEP stage's job and is P2-4b; a run
        // with `channelIdOnly` set therefore does no work here rather than pretending to refresh all
        // sources. P2-4b replaces this with a per-channel path.
        val active = if (options.channelIdOnly == null) providers.toList() else emptyList()

        logger.i(
            LogCategory.SOURCE,
            EventCodes.SRC_REFRESH_START,
            "refresh started",
            mapOf(
                "trigger" to options.trigger.name,
                "sources" to active.size,
                "budgetMs" to options.budgetMs,
                "playing" to playback.isPlaybackActive(),
                "session" to session,
            ),
        )
        emit(progress(RefreshPhase.FETCH, 0, active.size, 0, 0, startedAt))

        // --- Fetch (parallel, budget-gated) ---
        val fetch = fetchSources(active, budget, governor)
        var interruption: RefreshInterruption? = fetch.interruption
        emit(progress(RefreshPhase.FETCH, fetch.succeeded, active.size, fetch.succeeded, fetch.failed, startedAt, interruption))

        // --- Parse is done inside each provider; report the entries it produced ---
        emit(progress(RefreshPhase.PARSE, fetch.entries.size, fetch.entries.size, fetch.succeeded, fetch.failed, startedAt, interruption))

        // --- Normalize + Dedupe (pure, docs/02 §6.1) ---
        val normalized = PlaylistNormalizer.normalize(fetch.entries)
        emit(progress(RefreshPhase.NORMALIZE, normalized.entries.size, fetch.entries.size, 0, 0, startedAt, interruption))
        logger.d(
            LogCategory.SOURCE,
            EventCodes.SRC_DEDUPE,
            "dedupe done",
            mapOf(
                "raw" to normalized.streamDedupe.raw,
                "unique" to normalized.streamDedupe.unique,
                "channels" to normalized.channels.size,
            ),
        )
        emit(progress(RefreshPhase.DEDUPE, normalized.streamDedupe.unique, normalized.streamDedupe.raw, 0, 0, startedAt, interruption))

        // --- Cap (docs/02 §6.1: candidate ceiling) ---
        val capped = capCandidates(normalized, limits.capCandidates)

        // --- Persist the candidate set so every shallow verdict lands on a stable stream id ---
        val mapped = ChannelMapper.toDomain(capped)
        val persisted = persistMergingHealth(mapped)

        // --- Shallow reachability (parallel, budget-gated, resume-aware) ---
        val shallow = shallowValidate(persisted, budget, governor)
        interruption = shallow.interruption ?: interruption
        emit(
            progress(
                RefreshPhase.SHALLOW,
                shallow.checked + shallow.skipped,
                persisted.size,
                shallow.ok,
                shallow.failed,
                startedAt,
                interruption,
            ),
        )

        val elapsed = clock.nowMs() - startedAt
        logger.i(
            LogCategory.SOURCE,
            EventCodes.SRC_REFRESH_DONE,
            "refresh done",
            mapOf(
                "phase" to RefreshPhase.DONE.name,
                "ok" to shallow.ok,
                "fail" to shallow.failed,
                "skipped" to shallow.skipped,
                "elapsedMs" to elapsed,
                "interrupted" to interruption?.reason?.name,
                "session" to session,
            ),
        )
        emit(
            progress(
                RefreshPhase.DONE,
                shallow.checked + shallow.skipped,
                persisted.size,
                shallow.ok,
                shallow.failed,
                startedAt,
                interruption,
            ),
        )
    }

    // --- Fetch -----------------------------------------------------------------------------------

    private suspend fun fetchSources(
        sources: List<SourceProvider>,
        budget: RefreshBudget,
        governor: ConcurrencyGovernor,
    ): FetchResult {
        val admitted = ArrayList<SourceProvider>(sources.size)
        var admittedInterruption: RefreshInterruption? = null
        for (provider in sources) {
            if (!budget.canAdmitNext(StageProfiles.FETCH)) {
                admittedInterruption = RefreshInterruption(InterruptionReason.BUDGET_EXCEEDED, RefreshPhase.FETCH)
                break
            }
            admitted += provider
        }

        val entries = ArrayList<RawEntry>()
        var ok = 0
        var failed = 0
        val outcomes = coroutineScope {
            val semaphore = Semaphore(governor.fetchConcurrency())
            admitted.map { provider ->
                async {
                    semaphore.withPermit {
                        // A source that throws a non-cancellation exception must not kill the run.
                        try {
                            provider.fetch(clock)
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            AppResult.Err(AppError.unknown(EventCodes.SRC_FETCH_FAIL, e))
                        }
                    }
                }
            }.awaitAll()
        }
        for (outcome in outcomes) {
            when (outcome) {
                is AppResult.Ok -> { ok++; entries += outcome.value }
                is AppResult.Err -> failed++
            }
        }
        return FetchResult(entries = entries, succeeded = ok, failed = failed, interruption = admittedInterruption)
    }

    // --- Cap -------------------------------------------------------------------------------------

    /** Keeps at most [cap] of the deduped entries (docs/02 §6.1). Order is preserved. */
    private fun capCandidates(playlist: NormalizedPlaylist, cap: Int): NormalizedPlaylist {
        if (cap <= 0 || playlist.entries.size <= cap) return playlist
        val keptHashes = playlist.entries.take(cap).map { it.urlHash }.toHashSet()
        val keptChannels = playlist.channels
            .mapNotNull { channel ->
                val streams = channel.streams.filter { it.urlHash in keptHashes }
                if (streams.isEmpty()) null else channel.copy(streams = streams)
            }
        return playlist.copy(channels = keptChannels, entries = playlist.entries.take(cap))
    }

    // --- Persist (merge prior health so a re-run does not wipe the checkpoint) ---------------------

    private suspend fun persistMergingHealth(mapped: MappedCatalog): List<Stream> {
        val existing = HashMap<Pair<Long, String>, Stream>()
        for (channel in mapped.channels) {
            for (stream in streamRepository.candidates(channel.id)) {
                existing[stream.channelId to stream.urlHash] = stream
            }
        }
        val merged = mapped.streams.map { incoming ->
            val previous = existing[incoming.channelId to incoming.urlHash] ?: return@map incoming
            incoming.copy(
                quality = previous.quality,
                videoCodec = previous.videoCodec,
                audioCodec = previous.audioCodec,
                width = previous.width,
                height = previous.height,
                score = previous.score,
                priority = previous.priority,
                lastOkAtMs = previous.lastOkAtMs,
                lastCheckAtMs = previous.lastCheckAtMs,
                failCount = previous.failCount,
                lastError = previous.lastError,
                disabled = previous.disabled,
            )
        }
        streamRepository.upsertAll(merged)
        return merged
    }

    // --- Shallow ---------------------------------------------------------------------------------

    private suspend fun shallowValidate(
        streams: List<Stream>,
        budget: RefreshBudget,
        governor: ConcurrencyGovernor,
    ): ShallowResult {
        val nowMs = clock.nowMs()
        val pending = ArrayList<Stream>(streams.size)
        var skipped = 0
        var interruption: RefreshInterruption? = null
        for (stream in streams) {
            coroutineContext.ensureActive()
            if (isFresh(stream, nowMs)) { skipped++; continue }
            if (!budget.canAdmitNext(StageProfiles.SHALLOW)) {
                interruption = RefreshInterruption(InterruptionReason.BUDGET_EXCEEDED, RefreshPhase.SHALLOW)
                logger.d(
                    LogCategory.SOURCE,
                    EventCodes.SRC_REFRESH_SKIP,
                    "budget exhausted, stop admitting",
                    mapOf("phase" to RefreshPhase.SHALLOW.name, "remainingMs" to budget.remainingMs()),
                )
                break
            }
            pending += stream
        }

        val shallowValidators = validators
            .filter { it.stage == ValidationStage.SHALLOW }
            .sortedBy { it.order }
        val timeoutMs = limits.shallowTimeoutMs

        val verdicts = coroutineScope {
            val semaphore = Semaphore(governor.shallowConcurrency())
            pending.map { stream ->
                async {
                    semaphore.withPermit {
                        val passed = validateOne(stream, shallowValidators, timeoutMs, nowMs)
                        stream to passed
                    }
                }
            }.awaitAll()
        }
        var okCount = 0
        var failCount = 0
        for ((stream, passed) in verdicts) {
            if (passed.ok) okCount++ else failCount++
            streamRepository.recordOutcome(
                stream.id,
                StreamOutcome(
                    streamId = stream.id,
                    ok = passed.ok,
                    atMs = clock.nowMs(),
                    detail = passed.detail,
                    failure = passed.failure,
                ),
            )
        }
        return ShallowResult(
            checked = verdicts.size,
            ok = okCount,
            failed = failCount,
            skipped = skipped,
            interruption = interruption,
        )
    }

    private suspend fun validateOne(
        stream: Stream,
        ordered: List<StreamValidator>,
        timeoutMs: Long,
        nowMs: Long,
    ): Verdict {
        val target = StreamTarget(url = stream.url, userAgent = stream.userAgent, referrer = stream.referrer)
        val ctx = ProbeContext(
            stage = ValidationStage.SHALLOW,
            timeoutMs = timeoutMs,
            engineCaps = emptySet(),
            nowMs = nowMs,
        )
        var last: Verdict = Verdict(ok = true, detail = "no validator", failure = null)
        for (validator in ordered) {
            coroutineContext.ensureActive()
            val result = try {
                validator.validate(target, ctx)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = Verdict(false, "validator ${validator.id} threw ${e::class.simpleName}", null)
                break
            }
            if (!result.passed) {
                last = Verdict(false, "${validator.id}: ${result.detail}", failureOf(result.evidence))
                break
            }
            last = Verdict(true, result.detail, null)
        }
        return last
    }

    /**
     * A stream is fresh (re-run can skip it) when it succeeded within [healthyTtlMs], or failed
     * within [failedTtlMs] (docs/02 §6.1 incremental rules). Never-checked streams are not fresh.
     */
    private fun isFresh(stream: Stream, nowMs: Long): Boolean {
        val lastOk = stream.lastOkAtMs
        if (lastOk != null && nowMs - lastOk < healthyTtlMs) return true
        val lastCheck = stream.lastCheckAtMs
        return stream.failCount > 0 && lastCheck != null && nowMs - lastCheck < failedTtlMs
    }

    private fun failureOf(evidence: Map<String, Any?>): FailureClass? =
        (evidence["failure"] as? String)?.let { name ->
            FailureClass.entries.firstOrNull { it.name == name }
        }

    private fun progress(
        phase: RefreshPhase,
        done: Int,
        total: Int,
        ok: Int,
        fail: Int,
        startedAt: Long,
        interruption: RefreshInterruption? = null,
    ): RefreshProgress = RefreshProgress(
        phase = phase,
        done = done,
        total = total,
        okCount = ok,
        failCount = fail,
        elapsedMs = clock.nowMs() - startedAt,
        interrupted = interruption,
    )

    private class FetchResult(
        val entries: List<RawEntry>,
        val succeeded: Int,
        val failed: Int,
        val interruption: RefreshInterruption?,
    )

    private class ShallowResult(
        val checked: Int,
        val ok: Int,
        val failed: Int,
        val skipped: Int,
        val interruption: RefreshInterruption?,
    )

    private class Verdict(
        val ok: Boolean,
        val detail: String,
        val failure: FailureClass?,
    )
}
