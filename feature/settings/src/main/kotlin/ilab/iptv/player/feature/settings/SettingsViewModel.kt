package ilab.iptv.player.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import ilab.iptv.player.core.domain.refresh.EpgSettingsStore
import ilab.iptv.player.core.domain.refresh.RefreshScheduleSettings
import ilab.iptv.player.core.domain.source.SourceManagementPort
import ilab.iptv.player.core.log.DeviceInfo
import ilab.iptv.player.core.log.FileSink
import ilab.iptv.player.core.log.LogBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * The settings page's data (docs/04 P2-8 item 1). It only *reads* ports and the two log switches the
 * page owns (`LogBus.minLevel` = 记录级别, `FileSink.enabled` = 写入日志文件, docs/03 §14) — the same
 * two knobs the P0-6 console already had, now reachable from the settings level.
 *
 * Everything else on the page is a value or a link to a screen that already exists, which is what
 * keeps P2-8's skeleton from re-implementing P2-6's source manager or P0-6/P1-8's device console.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logBus: LogBus,
    private val fileSink: FileSink,
    private val sources: SourceManagementPort,
    private val refreshSettings: RefreshScheduleSettings,
    private val epgSettings: EpgSettingsStore,
) : ViewModel() {

    private val _facts = MutableStateFlow(initialFacts())
    val facts: StateFlow<SettingsFacts> = _facts.asStateFlow()

    /** Re-reads the subscriptions and the switches; cheap (one `SELECT` + two field reads). */
    suspend fun load() {
        val managed = sources.list()
        val freshestFetch = managed.filter { it.lastFetchAtMs != null }
            .maxByOrNull { it.lastFetchAtMs ?: 0L }
        val epg = epgSettings.read()
        _facts.value = initialFacts().copy(
            sourceCount = managed.size,
            enabledSourceCount = managed.count { it.enabled },
            lastRefreshSummary = lastRefreshSummary(managed.isEmpty(), freshestFetch?.lastFetchAtMs, freshestFetch?.entryCount),
            epgEnabled = epg.enabled,
            epgMinIntervalMs = epg.minIntervalMs,
        )
    }

    /**
     * 记录级别 cycles INFO → DEBUG → VERBOSE → INFO, exactly the cycle the P0-6 console's button
     * uses, because both write the same `LogBus` field. The panel's *view* filter is a different
     * knob (it does not change what gets recorded) — see [ilab.iptv.player.feature.settings.diag.DiagLogFilter].
     */
    fun cycleLogLevel(): LogLevel {
        val next = when (logBus.minLevel) {
            LogLevel.INFO -> LogLevel.DEBUG
            LogLevel.DEBUG -> LogLevel.VERBOSE
            else -> LogLevel.INFO
        }
        logBus.minLevel = next
        _facts.value = _facts.value.copy(logLevel = next.name)
        return next
    }

    /** The "写入日志文件" switch of docs/03 §14. */
    fun toggleFileLog(): Boolean {
        fileSink.enabled = !fileSink.enabled
        _facts.value = _facts.value.copy(fileLogEnabled = fileSink.enabled)
        return fileSink.enabled
    }

    /**
     * EPG-SETTINGS-1: the EPG master switch. Writes through the store (so it survives a restart) and
     * returns the new state; `:app`'s scheduler/coordinator read the same store per trigger, so their
     * next decision already obeys the new value without touching the existing programme data.
     */
    fun toggleEpg(): Boolean {
        val current = epgSettings.read()
        val next = !current.enabled
        epgSettings.write(current.copy(enabled = next))
        _facts.value = _facts.value.copy(epgEnabled = next)
        return next
    }

    /** EPG-SETTINGS-1: cycles the freshness threshold through the presets and persists the new value. */
    fun cycleEpgFreshness(): Long {
        val current = epgSettings.read()
        val next = EpgRefreshSettings.nextMinInterval(current.minIntervalMs)
        epgSettings.write(current.copy(minIntervalMs = next))
        _facts.value = _facts.value.copy(epgMinIntervalMs = next)
        return next
    }

    private fun initialFacts(): SettingsFacts {
        // EPG-SETTINGS-1: one read, so the first frame reflects a persisted change instead of defaults.
        val epg = epgSettings.read()
        return SettingsFacts(
            sourceCount = 0,
            enabledSourceCount = 0,
            lastRefreshSummary = NOT_LOADED,
            refreshMinuteOfDay = refreshSettings.refreshAtMinuteOfDay(),
            // P2-5's policy is wired in `:app` with the frozen default (3 deferrals / 30 min); the page
            // reports it rather than offering a switch that does not exist yet.
            playbackAvoidanceEnabled = true,
            logLevel = logBus.minLevel.name,
            fileLogEnabled = fileSink.enabled,
            epgEnabled = epg.enabled,
            epgMinIntervalMs = epg.minIntervalMs,
            appVersion = DeviceInfo.appVersion(context),
            deviceSummary = DeviceInfo.deviceSummary(),
        )
    }

    private fun lastRefreshSummary(noSources: Boolean, lastFetchAtMs: Long?, entries: Int?): String = when {
        noSources -> "没有订阅（当前是内置快照）"
        lastFetchAtMs == null -> "还没刷新过"
        else -> "最近 ${localTime(lastFetchAtMs)} · ${entries?.let { "$it 条" } ?: "无结果"}"
    }

    private fun localTime(atMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(atMs))

    private companion object {
        const val NOT_LOADED = "读取中…"
    }
}
