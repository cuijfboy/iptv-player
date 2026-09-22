package ilab.iptv.player.feature.settings.diag

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.common.Redactor
import ilab.iptv.player.core.domain.refresh.RefreshScheduleSettings
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.source.SourceManagementPort
import ilab.iptv.player.core.log.DeviceInfo
import ilab.iptv.player.core.log.FileSink
import ilab.iptv.player.core.log.LogBus
import ilab.iptv.player.core.log.MemoryRingSink
import ilab.iptv.player.core.model.ChannelFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * The diagnostics panel's state and its two actions (docs/03 §7, §8).
 *
 * It reads only what already exists — the injected `ChannelRepository` / `SourceManagementPort` ports,
 * the same `MemoryRingSink` and `FileSink` singletons the bus writes to, and the platform facts — so
 * this round adds no data channel of its own (dispatch item 2 and item 3).
 *
 * Actions are `suspend` and launched by the screen's `lifecycleScope`, the same shape as
 * `SourceManagementViewModel`: it keeps the panel testable off-device and avoids a second dispatcher
 * policy. The file work of an export hops to [Dispatchers.IO] because a zip of seven days of JSONL is
 * not main-thread work.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clock: Clock,
    private val logger: Logger,
    private val redactor: Redactor,
    private val ring: MemoryRingSink,
    private val logBus: LogBus,
    private val fileSink: FileSink,
    private val channels: ChannelRepository,
    private val sources: SourceManagementPort,
    private val refreshSettings: RefreshScheduleSettings,
) : ViewModel() {

    private val _blocks = MutableStateFlow<List<DiagBlock>>(emptyList())
    val blocks: StateFlow<List<DiagBlock>> = _blocks.asStateFlow()

    /** Non-null while an export runs: the line the progress indicator shows. */
    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    /** Re-reads every source behind the overview. Cheap enough for `onResume` (two queries + a read). */
    suspend fun refresh() {
        val device = AndroidDiagFacts.read(context)
        val items = channels.observe(ChannelFilter(group = null, favoritesOnly = false, includeHidden = true, query = null)).first()
        val managed = sources.list()
        val events = ring.snapshot()
        val status = fileSink.status()
        val input = DiagOverviewInput(
            appVersion = DeviceInfo.appVersion(context),
            abi = device.abi,
            sdk = device.sdk,
            deviceModel = device.deviceModel,
            usedMemoryMb = device.usedMemoryMb,
            maxMemoryMb = device.maxMemoryMb,
            storageFreeBytes = device.storageFreeBytes,
            storageTotalBytes = device.storageTotalBytes,
            network = device.network,
            channelCount = items.size,
            groupCount = items.map { it.channel.groupKey }.distinct().size,
            streamCount = items.sumOf { it.streams.size },
            hiddenCount = items.count { it.channel.hidden },
            favoriteCount = items.count { it.channel.favorite },
            sources = managed.map {
                DiagSourceLine(
                    label = it.label,
                    enabled = it.enabled,
                    entries = it.entryCount,
                    result = when {
                        it.lastResult == null -> DiagOverview.RESULT_NEVER
                        it.lastResult == ilab.iptv.player.core.domain.source.SourceFetchResult.OK ->
                            DiagOverview.RESULT_OK

                        else -> DiagOverview.RESULT_FAILED
                    },
                    atMs = it.lastFetchAtMs,
                )
            },
            playback = DiagOverview.playbackOf(events),
            logLevel = logBus.minLevel.name,
            fileLogEnabled = fileSink.enabled,
            ringSize = ring.size,
            ringCapacity = ring.capacity,
            fileLogSummary = fileSummary(status),
            lastRefresh = lastRefreshText(managed.mapNotNull { it.lastFetchAtMs }.maxOrNull(), managed.size),
        )
        _blocks.value = DiagOverview.build(input)
    }

    /** The panel's live tail: the ring's contents, filtered (no second buffer — dispatch item 2). */
    fun logs(query: DiagLogQuery): List<LogEvent> = DiagLogFilter.apply(ring.snapshot(), query)

    fun logLevel(): LogLevel = logBus.minLevel

    fun setLogLevel(level: LogLevel) {
        logBus.minLevel = level
    }

    fun fileLogEnabled(): Boolean = fileSink.enabled

    fun setFileLogEnabled(enabled: Boolean) {
        fileSink.enabled = enabled
    }

    fun clearRing() {
        ring.clear()
    }

    /** Everything the log half of the panel needs to know about the file sink's health. */
    fun fileSinkSummary(): String = fileSummary(fileSink.status())

    fun cleanupLogs(): Int = fileSink.cleanupNow()

    /**
     * The one-click export (docs/03 §8). Order matters: flush the bus first, so the events the user
     * just triggered are **in** the package instead of still sitting in the dispatch queue.
     */
    suspend fun export(): DiagPackageResult {
        val startedMs = clock.nowMs()
        logger.d(
            category = LogCategory.DB,
            code = EventCodes.DB_UPSERT,
            message = "diagnostics export started",
            fields = mapOf("table" to EXPORT_TABLE, "op" to "start"),
        )
        logger.flush(EXPORT_FLUSH_TIMEOUT_MS)
        return withContext(Dispatchers.IO) {
            val target = DiagStorage.exportDirectory(context)
            if (target == null) {
                val result = DiagPackageResult.Failed("这台设备没有外部文件目录，无法导出")
                logExportResult(result, startedMs)
                result
            } else {
                val result = DiagPackageWriter(redactor).write(
                    request = packageRequest(),
                    targetDir = target,
                    onProgress = { step -> _progress.value = step },
                )
                logExportResult(result, startedMs)
                _progress.value = null
                result
            }
        }
    }

    /** Success and failure are both visible in the log (dispatch item 4). */
    private fun logExportResult(result: DiagPackageResult, startedMs: Long) {
        val costMs = clock.nowMs() - startedMs
        when (result) {
            is DiagPackageResult.Written -> logger.i(
                category = LogCategory.DB,
                code = EventCodes.DB_UPSERT,
                message = "diagnostics export ok",
                fields = mapOf(
                    "table" to EXPORT_TABLE,
                    "op" to "ok",
                    "entries" to result.report.entries.size,
                    "logFiles" to result.report.logFileCount,
                    "logLines" to result.report.logLines,
                    "bytes" to result.report.sizeBytes,
                    "ms" to costMs,
                ),
            )

            is DiagPackageResult.Failed -> logger.e(
                category = LogCategory.DB,
                code = EventCodes.DB_FAIL,
                message = "diagnostics export failed",
                fields = mapOf("table" to EXPORT_TABLE, "op" to "fail", "err" to result.message, "ms" to costMs),
            )
        }
    }

    /** The package's contents, from the same facts the overview shows (docs/03 §8). */
    private suspend fun packageRequest(): DiagPackageRequest {
        val device = AndroidDiagFacts.read(context)
        val items = channels.observe(ChannelFilter(group = null, favoritesOnly = false, includeHidden = true, query = null)).first()
        val managed = sources.list()
        val status = fileSink.status()
        val events = ring.snapshot()
        val playback = DiagOverview.playbackOf(events)
        val exportedAtMs = clock.nowMs()

        val meta: Map<String, Any?> = linkedMapOf(
            "app" to context.packageName,
            "version" to DeviceInfo.appVersion(context),
            "buildType" to buildType(),
            "exportedAt" to exportedAtMs,
            "exportedAtLocal" to localTime(exportedAtMs),
            "device" to linkedMapOf(
                "model" to device.deviceModel,
                "abi" to device.abi,
                "sdk" to device.sdk,
                "android" to Build.VERSION.RELEASE,
            ),
            "memoryMb" to linkedMapOf("used" to device.usedMemoryMb, "max" to device.maxMemoryMb),
            "storageBytes" to linkedMapOf(
                "free" to device.storageFreeBytes,
                "total" to device.storageTotalBytes,
            ),
            "network" to device.network,
            "settings" to linkedMapOf(
                "logLevel" to logBus.minLevel.name,
                "fileLog" to fileSink.enabled,
                "refreshAtMinuteOfDay" to refreshSettings.refreshAtMinuteOfDay(),
            ),
            "logRing" to linkedMapOf("size" to ring.size, "capacity" to ring.capacity),
        )

        val stats: Map<String, Any?> = linkedMapOf(
            "channels" to items.size,
            "groups" to items.map { it.channel.groupKey }.distinct().size,
            "streams" to items.sumOf { it.streams.size },
            "hidden" to items.count { it.channel.hidden },
            "favorites" to items.count { it.channel.favorite },
            "sources" to managed.map { source ->
                linkedMapOf(
                    "label" to source.label,
                    "url" to source.url,
                    "enabled" to source.enabled,
                    "entries" to source.entryCount,
                    "lastFetchAt" to source.lastFetchAtMs,
                    "lastResult" to source.lastResult?.name,
                )
            },
            "playback" to linkedMapOf(
                "prepareStarts" to playback.starts,
                "firstFrames" to playback.firstFrames,
                "failovers" to playback.failovers,
                "lastCostMs" to playback.lastCostMs,
                "lastFailCode" to playback.lastFailCode,
            ),
            "logFile" to linkedMapOf(
                "enabled" to status.enabled,
                "healthy" to status.healthy,
                "files" to status.fileCount,
                "bytes" to status.totalBytes,
                "writtenLines" to status.writtenLines,
                "rotations" to status.rotations,
                "deletedFiles" to status.deletedFiles,
                "lastError" to status.lastError,
            ),
        )

        return DiagPackageRequest(
            meta = meta,
            stats = stats,
            overview = _blocks.value,
            logFiles = logFiles(),
            extractionLines = DiagStorage.extractionLines(context),
            exportEpochMs = exportedAtMs,
        )
    }

    /**
     * What goes under `logs/` in the package: every file in the log directory — the JSONL segments and
     * a `crash-*.txt` if a later round's `CrashSink` ever writes one (docs/03 §8). Empty when the
     * device has no external files directory.
     */
    private fun logFiles(): List<File> {
        val directory = fileSink.status().directory ?: return emptyList()
        return File(directory).listFiles()?.filter { it.isFile }.orEmpty()
    }

    private fun fileSummary(status: ilab.iptv.player.core.log.FileSinkStatus): String =
        if (status.directory == null) {
            "不可用（无外部目录）"
        } else {
            "${status.fileCount} 个 / ${DiagOverview.bytes(status.totalBytes)}" +
                (status.activeFile?.let { " / 活跃 $it" } ?: "") +
                if (status.healthy) "" else " / 异常：${status.lastError}"
        }

    private fun lastRefreshText(lastRefreshAtMs: Long?, sourceCount: Int): String = when {
        sourceCount == 0 -> "没有订阅（当前是内置示例表）"
        lastRefreshAtMs == null -> "还没刷新过（等每日 06:00 或手动刷新）"
        else -> "${localTime(lastRefreshAtMs)}（${(clock.nowMs() - lastRefreshAtMs) / 60_000L} 分钟前）"
    }

    private fun localTime(atMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(atMs))

    private fun buildType(): String =
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) "debug" else "release"

    private companion object {
        /** docs/03 §3.3 has no `DIAG_*` code yet; the floor's convention is `DB_UPSERT` + `op=` (see `SourceManagementPort`). */
        const val EXPORT_TABLE = "diag_export"

        const val EXPORT_FLUSH_TIMEOUT_MS = 2_000L
    }
}
