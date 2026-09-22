package ilab.iptv.player.core.data.refresh

import ilab.iptv.player.core.common.DispatcherProvider
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
import ilab.iptv.player.core.data.store.CatalogSink
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.StreamRepository
import ilab.iptv.player.core.domain.scoring.Scorer
import ilab.iptv.player.core.domain.scoring.Stability
import ilab.iptv.player.core.domain.selection.SelectionRules
import ilab.iptv.player.core.domain.selection.StreamSelector
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.DeviceProfile
import ilab.iptv.player.core.model.EngineCapability
import ilab.iptv.player.core.model.InterruptionReason
import ilab.iptv.player.core.model.ProbeContext
import ilab.iptv.player.core.model.RawEntry
import ilab.iptv.player.core.model.RefreshInterruption
import ilab.iptv.player.core.model.RefreshOptions
import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.model.RefreshProgress
import ilab.iptv.player.core.model.Resolutions
import ilab.iptv.player.core.model.ScoreBreakdown
import ilab.iptv.player.core.model.ScoreInput
import ilab.iptv.player.core.model.SelectionInput
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.StreamOutcome
import ilab.iptv.player.core.model.StreamTarget
import ilab.iptv.player.core.model.ValidationResult
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
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.coroutineContext

/**
 * The docs/02 §6.1 refresh pipeline, both halves:
 *
 * `Fetch → Parse → Normalize → Dedupe → Cap → Shallow → Persist(candidates) → Deep → Score →
 * Select → Persist → DONE`
 *
 * P2-4a (worker-dev-a-12) delivered the front half — fetch, parse, normalize, dedupe, cap, shallow
 * reachability, plus the budget / checkpoint / cancellation framework. P2-4b (this round) adds the
 * back half:
 * - **Deep** ([ValidationStage.DEEP] validators, budget-gated, concurrency-governed): a real probe
 *   that pulls a manifest and a segment instead of only asking whether the host answers;
 * - **Score**: the pure docs/02 §6.1 model ([Scorer]) per probed stream, logged as `SRC_SCORE`;
 * - **Select**: per channel, one primary plus up to two backups ([StreamSelector] + [SelectionRules]),
 *   logged as `SRC_SELECT`, with a channel that has no verified stream kept and marked unavailable;
 * - **Persist**: one final upsert writing the probe's codecs/resolution, the score, the selection
 *   (`disabled`) and the deep verdict's health stamp.
 *
 * Invariants this class is responsible for:
 * - **deadline-driven budget** ([RefreshBudget]): the run never starts work it cannot finish; it
 *   stops admitting items and reports `RefreshInterruption(BUDGET_EXCEEDED)` instead of overrunning;
 * - **incremental / breakpoint-resumable**: every verdict lands as it is produced, a re-run skips
 *   streams whose health is fresh (24 h healthy / 6 h failed) and **reuses their stored score**, so
 *   two runs over an unchanged catalog produce identical rows — idempotent;
 * - **nothing is silently dropped**: a stream that is not selected keeps its row with
 *   `disabled = true`; a channel whose candidates all fail stays visible (docs/01 F2);
 * - **never overwrite what this run did not verify**: a stream the run did not probe keeps its
 *   stored score, health and `disabled` flag;
 * - **cancellation**: every suspend point is cancellable and `CancellationException` is rethrown
 *   (docs/02 §4.5 C5);
 * - **playback avoidance** (R7): [ConcurrencyGovernor] halves Fetch / Shallow / Deep concurrency
 *   while a session plays and `respectPlayback` is on.
 */
class RefreshSourcesUseCase @Inject constructor(
    private val dispatchers: DispatcherProvider,
    private val providers: Set<@JvmSuppressWildcards SourceProvider>,
    private val validators: Set<@JvmSuppressWildcards StreamValidator>,
    private val streamRepository: StreamRepository,
    private val channelRepository: ChannelRepository,
    private val catalogSink: CatalogSink,
    private val scorer: Scorer,
    private val selector: StreamSelector,
    private val device: DeviceProfile,
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
        val channelIdOnly = options.channelIdOnly
        val active = if (channelIdOnly == null) providers.toList() else emptyList()

        logger.i(
            LogCategory.SOURCE,
            EventCodes.SRC_REFRESH_START,
            "refresh started",
            mapOf(
                "trigger" to options.trigger.name,
                "sources" to active.size,
                "channelId" to channelIdOnly,
                "budgetMs" to options.budgetMs,
                "playing" to playback.isPlaybackActive(),
                "session" to session,
            ),
        )

        if (channelIdOnly != null) {
            // ON_DEMAND_SINGLE_CHANNEL (docs/01 F4 `ResolveFresh`): re-probe, re-score and re-select
            // exactly one channel's persisted streams. Fetch/Parse/Normalize/Dedupe/Cap have nothing
            // to do — the rows already exist — so this path starts at Shallow (freshness) and Deep.
            emit(progress(RefreshPhase.FETCH, 0, 0, 0, 0, startedAt))
            val channel = channelRepository.get(channelIdOnly)?.channel
            val candidates = streamRepository.candidates(channelIdOnly)
            val nowMs = clock.nowMs()
            val back = runBackHalf(
                channelIndex = listOfNotNull(channel).associateBy { it.id },
                candidates = candidates,
                shallowFailed = emptySet(),
                freshIds = candidates.filter { isFresh(it, nowMs) }.map { it.id }.toSet(),
                budget = budget,
                governor = governor,
                startedAt = startedAt,
            )
            finish(session, back, startedAt)
            emit(
                progress(
                    RefreshPhase.DONE,
                    back.primary + back.backup,
                    back.channels,
                    back.primary,
                    back.unavailable,
                    startedAt,
                    back.interruption,
                ),
            )
            return@flow
        }

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
        // The ids `ChannelMapper` mints are per-load, so they are resolved against the write seam
        // first: the channels land in the store and the streams are pointed at the ids it returned,
        // which is what keeps `stream.channel_id -> channel.id` satisfiable (卡 REFRESH-PERSIST-1).
        val resolved = resolveStreamChannelIds(ChannelMapper.toDomain(capped))
        // The mapped stream ids are per-load (`ChannelMapper`), and the shallow stage and the scorer
        // both address a stream **by id**, so the write above is followed by a read that swaps each
        // per-load id for the id of the row it actually landed on (卡 STREAM-ID-1).
        val persisted = alignStreamIds(persistMergingHealth(resolved))

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

        // --- Deep → Score → Select → Persist ---
        val back = runBackHalf(
            channelIndex = resolved.channels.associateBy { it.id },
            candidates = persisted,
            shallowFailed = shallow.failedIds,
            freshIds = shallow.skippedIds,
            budget = budget,
            governor = governor,
            startedAt = startedAt,
        )
        val interrupted = interruption ?: back.interruption
        finish(session, back, startedAt, interrupted)
        // `RefreshProgress` keeps P2-4a's shape: the terminal emission reports the **shallow**
        // reachability stage (the deep/score/select numbers travel in the `SRC_REFRESH_DONE` fields
        // and their own phase emissions above), so a consumer written against P2-4a sees no change.
        emit(
            progress(
                RefreshPhase.DONE,
                shallow.checked + shallow.skipped,
                persisted.size,
                shallow.ok,
                shallow.failed,
                startedAt,
                interrupted,
            ),
        )
    }.flowOn(dispatchers.io)

    // --- Back half: Deep → Score → Select → Persist ------------------------------------------------

    private suspend fun FlowCollector<RefreshProgress>.runBackHalf(
        channelIndex: Map<Long, Channel>,
        candidates: List<Stream>,
        shallowFailed: Set<Long>,
        freshIds: Set<Long>,
        budget: RefreshBudget,
        governor: ConcurrencyGovernor,
        startedAt: Long,
    ): BackHalf {
        if (candidates.isEmpty()) return BackHalf()

        val deepValidators = validators
            .filter { it.stage == ValidationStage.DEEP }
            .sortedBy { it.order }
        if (deepValidators.isEmpty()) {
            // No DEEP validator is bound (unit tests that only wire a shallow chain). Scoring without
            // evidence would overwrite good rows with a zero score, so the back half steps aside.
            // This is a configuration fact, not a per-stream refresh event, so it is not a
            // `SRC_REFRESH_SKIP` (which counts streams the run chose not to re-check); the terminal
            // `SRC_REFRESH_DONE` reports `deepEnabled = false`.
            return BackHalf(deepEnabled = false)
        }

        // --- Deep: probe everything the shallow stage did not already disprove or verify recently ---
        val verify = HashMap<Long, Verification>(candidates.size)
        for (stream in candidates) {
            verify[stream.id] = when {
                stream.id in shallowFailed -> Verification.failed("shallow validation failed")
                stream.id in freshIds ->
                    Verification.reused(verified = stream.failCount == 0 && stream.lastOkAtMs != null)
                else -> Verification.pending()
            }
        }
        val pending = candidates.filter { verify.getValue(it.id).kind == VerificationKind.PENDING }

        val admitted = ArrayList<Stream>(pending.size)
        var interruption: RefreshInterruption? = null
        for (stream in pending) {
            coroutineContext.ensureActive()
            if (!budget.canAdmitNext(StageProfiles.DEEP)) {
                interruption = RefreshInterruption(InterruptionReason.BUDGET_EXCEEDED, RefreshPhase.DEEP)
                logger.d(
                    LogCategory.SOURCE,
                    EventCodes.SRC_REFRESH_SKIP,
                    "budget exhausted, stop admitting",
                    mapOf("phase" to RefreshPhase.DEEP.name, "remainingMs" to budget.remainingMs()),
                )
                break
            }
            admitted += stream
        }

        val probed = if (admitted.isEmpty()) {
            emptyList()
        } else {
            coroutineScope {
                val semaphore = Semaphore(governor.deepConcurrency())
                admitted.map { stream ->
                    async {
                        semaphore.withPermit {
                            stream to validateChain(stream, deepValidators, limits.deepTimeoutMs, clock.nowMs())
                        }
                    }
                }.awaitAll()
            }
        }
        for ((stream, result) in probed) verify[stream.id] = Verification.probed(result)
        val deepOk = probed.count { it.second.passed }
        val deepFail = probed.size - deepOk
        emit(progress(RefreshPhase.DEEP, probed.size, candidates.size, deepOk, deepFail, startedAt, interruption))

        // --- Score (pure) ---
        val scores = HashMap<Long, ScoreBreakdown>(candidates.size)
        val fields = HashMap<Long, DeepFields>(candidates.size)
        var scored = 0
        for (stream in candidates) {
            val verification = verify.getValue(stream.id)
            val deep = if (verification.kind == VerificationKind.PROBED) {
                DeepEvidence.of(verification.result.evidence)
            } else {
                DeepFields(stream.videoCodec, stream.audioCodec, stream.width, stream.height, null)
            }
            fields[stream.id] = deep
            // Incremental rule: a stream this run did not probe keeps its stored score, so two runs
            // over an unchanged catalog cannot drift apart (docs/02 §6.1 断点续跑).
            if (verification.kind != VerificationKind.PROBED) {
                scores[stream.id] = ScoreBreakdown(total = stream.score, byRule = emptyMap())
                continue
            }
            val health = streamRepository.health(stream.id)
            val stability = Stability.of(
                if (verification.result.passed) {
                    health
                } else {
                    health.copy(
                        attempts = health.attempts + 1,
                        failures = health.failures + 1,
                        consecutiveFails = health.consecutiveFails + 1,
                    )
                },
            )
            val breakdown = scorer.score(
                ScoreInput(
                    stream = stream,
                    probe = verification.result,
                    videoCodec = deep.videoCodec,
                    audioCodec = deep.audioCodec,
                    width = deep.width,
                    height = deep.height,
                    stability = stability,
                    device = device,
                ),
            )
            scores[stream.id] = breakdown
            scored++
            logger.d(
                LogCategory.SOURCE,
                EventCodes.SRC_SCORE,
                "stream scored",
                mapOf(
                    "url" to urlDigest(stream.url),
                    "total" to breakdown.total,
                    "vcodec" to deep.videoCodec,
                    "acodec" to deep.audioCodec,
                    "w" to deep.width,
                    "h" to deep.height,
                    "stability" to stability,
                ) + breakdown.byRule.mapKeys { "rule.${it.key}" },
            )
        }
        emit(progress(RefreshPhase.SCORE, scored, candidates.size, 0, 0, startedAt, interruption))

        // --- Select (pure): one primary + up to two backups per channel ---
        val byChannel = candidates.groupBy { it.channelId }
        val selectedIds = HashSet<Long>(byChannel.size * SelectionRules.MAX_STREAMS_PER_CHANNEL)
        var primaryCount = 0
        var backupCount = 0
        var unavailableCount = 0
        for ((channelId, channelStreams) in byChannel) {
            coroutineContext.ensureActive()
            val channel = channelIndex[channelId] ?: continue
            val ranked = selector.rank(
                SelectionInput(
                    channel = channel,
                    // Rank on this run's scores: the persisted snapshot may still carry last run's
                    // (or no) score, and the §4.3 key is defined on the score of the stream being
                    // chosen (docs/02 §6.1 Select).
                    candidates = channelStreams.map { it.copy(score = scores.getValue(it.id).total) },
                    nowMs = clock.nowMs(),
                    // Ranking is the frozen docs/02 §4.3 key, which does not consume capabilities; the
                    // field is carried for the capability gate a later round adds.
                    engineCaps = emptySet<EngineCapability>(),
                    device = device,
                    health = emptyMap(),
                ),
            )
            val selection = SelectionRules.select(ranked) { verify.getValue(it.id).verified }
            selection.selected.forEach { selectedIds += it.id }
            if (selection.isAvailable) primaryCount++ else unavailableCount++
            backupCount += selection.backups.size
            logger.i(
                LogCategory.SOURCE,
                EventCodes.SRC_SELECT,
                "channel select",
                mapOf(
                    "channelId" to channelId,
                    "candidates" to channelStreams.size,
                    "primary" to selection.primary?.id,
                    "backup" to selection.backups.map { it.id },
                    "dropped" to selection.dropped.size,
                    "unavailable" to !selection.isAvailable,
                ),
            )
        }
        emit(
            progress(
                RefreshPhase.SELECT,
                byChannel.size,
                byChannel.size,
                primaryCount,
                unavailableCount,
                startedAt,
                interruption,
            ),
        )

        // --- Persist: codecs/resolution, score, selection and the deep health stamp in one upsert ---
        val nowMs = clock.nowMs()
        val updated = candidates.mapNotNull { stream ->
            val verification = verify.getValue(stream.id)
            val deep = fields.getValue(stream.id)
            val selected = stream.id in selectedIds
            when (verification.kind) {
                // Fresh: the stream was verified within its TTL, so its score and health stand; only
                // the selection verdict is (re)written.
                VerificationKind.REUSED -> stream.copy(disabled = !selected)
                // Not admitted before the deadline: this run has no deep verdict, so the row keeps
                // everything the store already holds — including the shallow verdict this run recorded
                // a moment ago (`StreamRepository.recordOutcome`). Writing the pre-shallow snapshot
                // back here would *erase* that verdict, and the next run would pay for the same probe
                // again, which is exactly what docs/02 §6.1's 断点续跑 rule forbids.
                VerificationKind.PENDING -> null
                VerificationKind.PROBED -> stream.copy(
                    quality = if (deep.width > 0 || deep.height > 0) {
                        Resolutions.qualityOf(deep.width, deep.height)
                    } else {
                        stream.quality
                    },
                    videoCodec = deep.videoCodec ?: stream.videoCodec,
                    audioCodec = deep.audioCodec ?: stream.audioCodec,
                    width = deep.width.takeIf { it > 0 } ?: stream.width,
                    height = deep.height.takeIf { it > 0 } ?: stream.height,
                    score = scores.getValue(stream.id).total,
                    disabled = !selected,
                    lastCheckAtMs = nowMs,
                    // A deep failure clears the "healthy" stamp — including the shallow stage's
                    // optimistic one — so the 6 h failure TTL governs the next run (docs/02 §6.1).
                    lastOkAtMs = if (verification.result.passed) nowMs else null,
                    failCount = if (verification.result.passed) 0 else stream.failCount + 1,
                    lastError = if (verification.result.passed) null else verification.errorText(),
                )
            }
        }
        if (updated.isNotEmpty()) {
            streamRepository.upsertAll(updated)
            logger.d(
                LogCategory.DB,
                EventCodes.DB_UPSERT,
                "selection persisted",
                mapOf("table" to "stream", "rows" to updated.size, "phase" to RefreshPhase.PERSIST.name),
            )
        }
        emit(progress(RefreshPhase.PERSIST, updated.size, candidates.size, 0, 0, startedAt, interruption))

        return BackHalf(
            interruption = interruption,
            channels = byChannel.size,
            primary = primaryCount,
            backup = backupCount,
            unavailable = unavailableCount,
            deepOk = deepOk,
            deepFail = deepFail,
        )
    }

    /** Terminal `SRC_REFRESH_DONE` + the final [RefreshProgress]. */
    private fun finish(
        session: String,
        back: BackHalf,
        startedAt: Long,
        interruption: RefreshInterruption? = back.interruption,
    ) {
        val elapsed = clock.nowMs() - startedAt
        logger.i(
            LogCategory.SOURCE,
            EventCodes.SRC_REFRESH_DONE,
            "refresh done",
            mapOf(
                "phase" to RefreshPhase.DONE.name,
                "channels" to back.channels,
                "selectedPrimary" to back.primary,
                "selectedBackup" to back.backup,
                "unavailable" to back.unavailable,
                "deepOk" to back.deepOk,
                "deepFail" to back.deepFail,
                "deepEnabled" to back.deepEnabled,
                "elapsedMs" to elapsed,
                "interrupted" to interruption?.reason?.name,
                "session" to session,
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

    /**
     * Points the mapped catalog at stored channel rows (卡 REFRESH-PERSIST-1). The pipeline writes
     * `stream` rows whose `channel_id` is a foreign key (docs/02 §5.1), and [ChannelMapper] ids are
     * minted per load — they are *not* database ids. So the channels go through the write seam first
     * ([CatalogSink.upsertChannels], which upserts on `(name_key, group_key)` and returns the stored
     * ids), and every stream is rewritten to the id its channel now has. A stream whose channel the
     * seam did not return is dropped: with no channel row the foreign key would reject it, and
     * attaching it to some other channel would be worse.
     *
     * Nothing is deleted, and a channel that already existed keeps its row and its user-owned columns,
     * so this is the additive half of the seam — an import still owns "replace". If the store rejects
     * the channel write (a storage failure, already logged as `DB_FAIL`), the seam returns an empty map
     * and this yields an empty catalog, so the run reports the channels it found but writes nothing
     * rather than failing every stream against a missing channel.
     */
    private suspend fun resolveStreamChannelIds(mapped: MappedCatalog): MappedCatalog {
        if (mapped.channels.isEmpty()) return mapped
        val storedIds = catalogSink.upsertChannels(mapped, clock.nowMs())
        if (storedIds.isEmpty()) return MappedCatalog(channels = emptyList(), streams = emptyList())
        val channels = mapped.channels.mapNotNull { channel ->
            storedIds[channel.id]?.let { channel.copy(id = it) }
        }
        val streams = mapped.streams.mapNotNull { stream ->
            storedIds[stream.channelId]?.let { stream.copy(channelId = it) }
        }
        return MappedCatalog(channels = channels, streams = streams)
    }

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

    /**
     * Re-addresses the streams of this run (卡 STREAM-ID-1). [ChannelMapper] mints a stream's id from
     * a per-load counter — the class KDoc says so: they "are not identity across refreshes" — and
     * that id is what [StreamRepository.upsertAll] keys *past*: the write goes to the row whose
     * `(channel_id, url_hash)` matches, keeping its id ([RoomCatalogWriter]).
     *
     * Everything after this point addresses a stream **by id**, so the per-load id has to go:
     * - the shallow stage stamps its verdict with `recordOutcome(stream.id, …)`, which resolves the
     *   row and appends the `play_history` row that owns it;
     * - the scorer reads the same row back with `health(stream.id)` for its `stability` factor.
     *
     * With the per-load id in place the verdict lands on whichever row happens to carry that id —
     * on a store that already had streams (the bundled snapshot, the previous refresh) that is a
     * *different* channel's stream, so the health stamps, the `play_history` trail and the score's
     * input are all attributed to the wrong row. This is the stream-side twin of
     * [resolveStreamChannelIds], which fixed the channel side of the same mismatch.
     *
     * A stream the store does not carry is dropped: with no row there is nothing to stamp, and the
     * per-load id would be exactly the mis-addressing this removes. In the normal path every stream
     * resolves (the write just before it succeeded); an empty result therefore means the store could
     * not be read (already logged `DB_FAIL` by the seam) or could not be written, and the run writes
     * no health rather than guessing.
     */
    private suspend fun alignStreamIds(streams: List<Stream>): List<Stream> {
        if (streams.isEmpty()) return streams
        val storedIds = catalogSink.resolveStreamIds(streams)
        if (storedIds.isEmpty()) return emptyList()
        return streams.mapNotNull { stream -> storedIds[stream.id]?.let { stream.copy(id = it) } }
    }

    // --- Shallow ---------------------------------------------------------------------------------

    private suspend fun shallowValidate(
        streams: List<Stream>,
        budget: RefreshBudget,
        governor: ConcurrencyGovernor,
    ): ShallowStage {
        val nowMs = clock.nowMs()
        val pending = ArrayList<Stream>(streams.size)
        val skipped = ArrayList<Stream>()
        var interruption: RefreshInterruption? = null
        for (stream in streams) {
            coroutineContext.ensureActive()
            if (isFresh(stream, nowMs)) { skipped += stream; continue }
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

        val verdicts = coroutineScope {
            val semaphore = Semaphore(governor.shallowConcurrency())
            pending.map { stream ->
                async {
                    semaphore.withPermit {
                        stream to validateChain(stream, shallowValidators, limits.shallowTimeoutMs, nowMs)
                    }
                }
            }.awaitAll()
        }
        var okCount = 0
        var failCount = 0
        for ((stream, result) in verdicts) {
            if (result.passed) okCount++ else failCount++
            streamRepository.recordOutcome(
                stream.id,
                StreamOutcome(
                    streamId = stream.id,
                    ok = result.passed,
                    atMs = clock.nowMs(),
                    detail = result.detail,
                    failure = failureOf(result.evidence),
                ),
            )
        }
        return ShallowStage(
            checked = verdicts.size,
            ok = okCount,
            failed = failCount,
            skipped = skipped.size,
            skippedIds = skipped.map { it.id }.toSet(),
            failedIds = verdicts.filterNot { it.second.passed }.map { it.first.id }.toSet(),
            interruption = interruption,
        )
    }

    /**
     * Runs the ordered validator chain for one stream, short-circuiting on the first failure
     * (docs/02 §4.4 E2). A validator that throws is contained: a probe bug fails the stream, it never
     * kills the run; a `CancellationException` is rethrown untouched (docs/02 §4.5 C5).
     */
    private suspend fun validateChain(
        stream: Stream,
        ordered: List<StreamValidator>,
        timeoutMs: Long,
        nowMs: Long,
    ): ValidationResult {
        if (ordered.isEmpty()) return ValidationResult(true, "no validator", mapOf("skipped" to true))
        val target = StreamTarget(url = stream.url, userAgent = stream.userAgent, referrer = stream.referrer)
        val ctx = ProbeContext(
            stage = ordered.first().stage,
            timeoutMs = timeoutMs,
            engineCaps = emptySet(),
            nowMs = nowMs,
        )
        var last = ValidationResult(true, "no validator", emptyMap())
        for (validator in ordered) {
            coroutineContext.ensureActive()
            val result = try {
                validator.validate(target, ctx)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                return ValidationResult(
                    passed = false,
                    detail = "validator ${validator.id} threw ${e::class.simpleName}",
                    evidence = mapOf("failure" to FailureClass.UNKNOWN.name, "phase" to validator.id),
                )
            }
            if (!result.passed) {
                return ValidationResult(false, "${validator.id}: ${result.detail}", result.evidence)
            }
            last = ValidationResult(true, "${validator.id}: ${result.detail}", result.evidence)
        }
        return last
    }

    /**
     * A stream is fresh (a re-run can skip it) when it succeeded within [healthyTtlMs], or failed
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

    /** `SRC_SCORE` logs a URL digest, not the URL: the log is exportable (docs/03 §11 脱敏). */
    private fun urlDigest(url: String): String =
        url.substringBefore("://", missingDelimiterValue = "") + "://…/" + url.substringAfterLast('/').take(12)

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

    /** What the back half found and wrote, for the terminal log line. */
    private class BackHalf(
        val interruption: RefreshInterruption? = null,
        val channels: Int = 0,
        val primary: Int = 0,
        val backup: Int = 0,
        val unavailable: Int = 0,
        val deepOk: Int = 0,
        val deepFail: Int = 0,
        val deepEnabled: Boolean = true,
    )

    private class FetchResult(
        val entries: List<RawEntry>,
        val succeeded: Int,
        val failed: Int,
        val interruption: RefreshInterruption?,
    )

    private class ShallowStage(
        val checked: Int,
        val ok: Int,
        val failed: Int,
        val skipped: Int,
        val skippedIds: Set<Long>,
        val failedIds: Set<Long>,
        val interruption: RefreshInterruption?,
    )

    private enum class VerificationKind { PROBED, REUSED, PENDING }

    /**
     * What this run knows about one stream's playability:
     * - [VerificationKind.PROBED]: the deep stage ran and produced a [ValidationResult];
     * - [VerificationKind.REUSED]: health was still fresh, so the previous verdict stands;
     * - [VerificationKind.PENDING]: no verdict (the deadline cut in) — the row is left alone.
     */
    private class Verification(
        val kind: VerificationKind,
        val verified: Boolean,
        val result: ValidationResult,
    ) {
        fun errorText(): String = failure?.name ?: result.detail

        private val failure: FailureClass?
            get() = (result.evidence["failure"] as? String)?.let { name ->
                FailureClass.entries.firstOrNull { it.name == name }
            }

        companion object {
            fun probed(result: ValidationResult): Verification =
                Verification(VerificationKind.PROBED, result.passed, result)

            fun reused(verified: Boolean): Verification =
                Verification(VerificationKind.REUSED, verified, ValidationResult(verified, "fresh (TTL)"))

            fun failed(detail: String): Verification = Verification(
                VerificationKind.PROBED,
                verified = false,
                result = ValidationResult(
                    passed = false,
                    detail = detail,
                    evidence = mapOf("failure" to FailureClass.UNKNOWN.name),
                ),
            )

            fun pending(): Verification = Verification(
                VerificationKind.PENDING,
                verified = false,
                result = ValidationResult(false, "not probed this run"),
            )
        }
    }
}
