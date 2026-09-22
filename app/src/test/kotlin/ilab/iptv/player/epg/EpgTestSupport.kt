package ilab.iptv.player.epg

import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.data.epg.EpgSourceStatusReader
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgCoverage
import ilab.iptv.player.core.model.EpgLoadReport
import ilab.iptv.player.core.model.EpgSourceStatus

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
