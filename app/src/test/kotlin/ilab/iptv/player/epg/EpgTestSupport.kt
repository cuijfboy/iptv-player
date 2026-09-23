package ilab.iptv.player.epg

import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.data.epg.EpgSourceStatusReader
import ilab.iptv.player.core.data.epg.EpgStoredGuideReader
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import ilab.iptv.player.core.domain.refresh.EpgSettingsStore
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgCoverage
import ilab.iptv.player.core.model.EpgLoadReport
import ilab.iptv.player.core.model.EpgSourceStatus
import ilab.iptv.player.core.model.EpgStoredGuide

/**
 * A settings store the test owns: the scheduler/coordinator read it per trigger/run, so a test can
 * change the switch or the freshness threshold between calls and observe the new decision
 * (EPG-SETTINGS-1). Sanitizes on the way in and out, like the production store, so a test that writes
 * an out-of-range value sees the clamped one.
 */
class FakeEpgSettingsStore(
    private var settings: EpgRefreshSettings = EpgRefreshSettings(),
) : EpgSettingsStore {

    /** How many times the value was read — proves the read happens per call, not once at construction. */
    var reads: Int = 0
        private set

    override fun read(): EpgRefreshSettings {
        reads++
        return settings.sanitized()
    }

    override fun write(settings: EpgRefreshSettings) {
        this.settings = settings.sanitized()
    }

    fun set(settings: EpgRefreshSettings) {
        this.settings = settings
    }
}

/** An `epg_source` table whose freshness the test chooses; nothing else is ever read from it. */
class FakeEpgSourceStatus(
    private var status: EpgSourceStatus = EpgSourceStatus(
        sources = 4,
        enabledSources = 4,
        lastFetchAtMs = null,
        lastResult = null,
    ),
) : EpgSourceStatusReader {

    var reads: Int = 0
        private set

    override suspend fun read(): EpgSourceStatus {
        reads++
        return status
    }

    fun set(lastFetchAtMs: Long?, lastResult: String? = null, sources: Int = 4) {
        status = EpgSourceStatus(sources, sources, lastFetchAtMs, lastResult)
    }
}

/**
 * The other half of the gate's input (BUG-20260922-016): what the stored guide looks like. The default
 * is the healthy case — channels bound and showing something — so a test only has to say so when it is
 * testing the *empty* arm, and the freshness tests keep measuring freshness alone.
 */
class FakeEpgStoredGuide(
    private var guide: EpgStoredGuide = EpgStoredGuide(channels = 571, matched = 153, programmed = 126),
) : EpgStoredGuideReader {

    var reads: Int = 0
        private set

    /**
     * Optional per-read answer, for the NEW-20260922-002 case: the catalogue is not there on the first
     * read and appears a few reads later, exactly like the seeding that lands ~2 s after a cold start.
     * When set it overrides whatever [set] stored.
     */
    var answerOn: ((Int) -> EpgStoredGuide)? = null

    override suspend fun read(): EpgStoredGuide {
        reads++
        answerOn?.let { return it(reads) }
        return guide
    }

    fun set(channels: Int, matched: Int, programmed: Int) {
        guide = EpgStoredGuide(channels = channels, matched = matched, programmed = programmed)
    }
}

/** Books the specs the scheduler hands over, so the request shape is asserted without WorkManager. */
class RecordingEpgWorkEnqueuer : EpgWorkEnqueuer {

    val specs = mutableListOf<EpgRefreshWorkSpec>()

    override fun enqueue(spec: EpgRefreshWorkSpec) {
        specs += spec
    }
}

/** An EPG pipeline whose answer the test chooses; no Hilt, no network, no Room. */
class FakeEpgRunner(
    private val result: AppResult<EpgLoadReport>? = null,
    private val error: Throwable? = null,
    private val delayMs: Long = 0L,
) : EpgRunner {

    var calls: Int = 0
        private set

    var lastOptions: EpgRunOptions? = null
        private set

    override suspend fun run(options: EpgRunOptions): AppResult<EpgLoadReport> {
        calls++
        lastOptions = options
        if (delayMs > 0L) kotlinx.coroutines.delay(delayMs)
        error?.let { throw it }
        return result ?: AppResult.Ok(report())
    }

    companion object {

        fun report(
            providers: Int = 4,
            programmes: Int = 177_447,
            matched: Int = 153,
            total: Int = 156,
            withProgrammes: Int = matched,
            interrupted: String? = null,
        ) = EpgLoadReport(
            providers = providers,
            channels = total,
            programmes = programmes,
            skipped = 0,
            coverage = EpgCoverage(
                matched = matched,
                total = total,
                byGroup = mapOf(ChannelGroup.CCTV to matched),
                byGroupTotal = mapOf(ChannelGroup.CCTV to total),
                withProgrammes = withProgrammes,
                byGroupWithProgrammes = mapOf(ChannelGroup.CCTV to withProgrammes),
            ),
            elapsedMs = 6_000L,
            interrupted = interrupted,
        )
    }
}
