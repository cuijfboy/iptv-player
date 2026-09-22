package ilab.iptv.player.feature.settings

import androidx.annotation.StringRes

/**
 * The settings skeleton of docs/04 P2-8 item 1: groups 源 / 播放 / 刷新 / 诊断 / 关于, every row
 * reachable with the remote and every row either opening an existing screen or reporting a value.
 *
 * This file is deliberately Android-free apart from resource ids: the *shape* of the page (which
 * groups exist, in what order, which row leads where) is the part worth unit-testing, and the screen
 * itself is then a dumb renderer over [SettingsCatalog.build]. Nothing here re-implements a page that
 * already exists — the source manager (`:feature:settings.SourceManagementActivity`) and the log /
 * device console (`:core:log.ui.LogConsoleActivity`, P0-6 + P1-8) are entered, not rebuilt.
 */
enum class SettingsGroup(@StringRes val titleRes: Int) {
    SOURCE(R.string.settings_group_source),
    PLAYBACK(R.string.settings_group_playback),
    REFRESH(R.string.settings_group_refresh),
    DIAGNOSTICS(R.string.settings_group_diagnostics),
    ABOUT(R.string.settings_group_about),
}

/** What activating a row does. [NONE] is a row that only reports (about/values). */
enum class SettingsDestination {
    NONE,
    SOURCE_MANAGEMENT,
    LOG_CONSOLE,
    DIAGNOSTICS,
    CYCLE_LOG_LEVEL,
    TOGGLE_FILE_LOG,
}

/** One row of the page. [summaryArgs] are `String.format` arguments for [summaryRes]. */
data class SettingsEntry(
    val id: String,
    val group: SettingsGroup,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val summaryArgs: List<Any> = emptyList(),
    val destination: SettingsDestination = SettingsDestination.NONE,
    val enabled: Boolean = true,
)

/**
 * Everything the page shows, gathered by the screen before [SettingsCatalog.build] turns it into
 * rows. A plain data class so the catalog can be tested without a device, a repository or a clock.
 */
data class SettingsFacts(
    val sourceCount: Int,
    val enabledSourceCount: Int,
    /** Already formatted by the screen (it owns the time zone); e.g. `最近 2026-09-22 06:00 · 412 条`. */
    val lastRefreshSummary: String,
    val refreshMinuteOfDay: Int,
    val playbackAvoidanceEnabled: Boolean,
    val logLevel: String,
    val fileLogEnabled: Boolean,
    val appVersion: String,
    val deviceSummary: String,
)

/**
 * docs/02 §8.1 navigation: `BrowseActivity → SettingsActivity → DiagnosticsFragment`. The browse
 * screen is the only parent of the settings page, and the diagnostics panel's parent is the settings
 * page — so BACK from the panel returns to the settings page and BACK from settings returns to the
 * browse screen (§8.1 返回键层级: 设置/诊断 → 浏览页).
 *
 * Kept as a pure map (instead of relying only on the activity stack) so the contract is asserted by a
 * test and can be shown as the on-screen hint a tester reads in a screenshot.
 */
enum class SettingsScreen { BROWSE, SETTINGS, DIAGNOSTICS }

object SettingsHierarchy {

    /** The screen BACK goes to, or `null` for the top of our stack (the browse screen). */
    fun parentOf(screen: SettingsScreen): SettingsScreen? = when (screen) {
        SettingsScreen.DIAGNOSTICS -> SettingsScreen.SETTINGS
        SettingsScreen.SETTINGS -> SettingsScreen.BROWSE
        SettingsScreen.BROWSE -> null
    }
}

/**
 * Builds the page's rows from [SettingsFacts]. The order of the groups is fixed by
 * [grouped] (源 → 播放 → 刷新 → 诊断 → 关于), which is what the remote's focus path follows.
 */
object SettingsCatalog {

    val GROUP_ORDER: List<SettingsGroup> = listOf(
        SettingsGroup.SOURCE,
        SettingsGroup.PLAYBACK,
        SettingsGroup.REFRESH,
        SettingsGroup.DIAGNOSTICS,
        SettingsGroup.ABOUT,
    )

    fun build(facts: SettingsFacts): List<SettingsEntry> = listOf(
        // --- 源 ---------------------------------------------------------------------------------
        SettingsEntry(
            id = "source.manage",
            group = SettingsGroup.SOURCE,
            titleRes = R.string.settings_source_manage,
            summaryRes = R.string.settings_source_manage_summary,
            summaryArgs = listOf(facts.sourceCount, facts.enabledSourceCount),
            destination = SettingsDestination.SOURCE_MANAGEMENT,
        ),
        SettingsEntry(
            id = "source.refresh",
            group = SettingsGroup.SOURCE,
            titleRes = R.string.settings_source_refresh,
            summaryRes = R.string.settings_source_refresh_summary,
            summaryArgs = listOf(facts.lastRefreshSummary),
            destination = SettingsDestination.NONE,
        ),

        // --- 播放 -------------------------------------------------------------------------------
        SettingsEntry(
            id = "playback.avoidance",
            group = SettingsGroup.PLAYBACK,
            titleRes = R.string.settings_playback_avoidance,
            summaryRes = if (facts.playbackAvoidanceEnabled) {
                R.string.settings_playback_avoidance_on
            } else {
                R.string.settings_playback_avoidance_off
            },
            destination = SettingsDestination.NONE,
        ),

        // --- 刷新 -------------------------------------------------------------------------------
        SettingsEntry(
            id = "refresh.schedule",
            group = SettingsGroup.REFRESH,
            titleRes = R.string.settings_refresh_schedule,
            summaryRes = R.string.settings_refresh_schedule_summary,
            summaryArgs = listOf(formatMinuteOfDay(facts.refreshMinuteOfDay)),
            destination = SettingsDestination.NONE,
        ),

        // --- 诊断 -------------------------------------------------------------------------------
        SettingsEntry(
            id = "diag.panel",
            group = SettingsGroup.DIAGNOSTICS,
            titleRes = R.string.settings_diag_panel,
            summaryRes = R.string.settings_diag_panel_summary,
            destination = SettingsDestination.DIAGNOSTICS,
        ),
        SettingsEntry(
            id = "diag.console",
            group = SettingsGroup.DIAGNOSTICS,
            titleRes = R.string.settings_diag_console,
            summaryRes = R.string.settings_diag_console_summary,
            destination = SettingsDestination.LOG_CONSOLE,
        ),
        SettingsEntry(
            id = "diag.level",
            group = SettingsGroup.DIAGNOSTICS,
            titleRes = R.string.settings_diag_level,
            summaryRes = R.string.settings_diag_level_summary,
            summaryArgs = listOf(facts.logLevel),
            destination = SettingsDestination.CYCLE_LOG_LEVEL,
        ),
        SettingsEntry(
            id = "diag.file",
            group = SettingsGroup.DIAGNOSTICS,
            titleRes = R.string.settings_diag_file,
            summaryRes = if (facts.fileLogEnabled) {
                R.string.settings_diag_file_on
            } else {
                R.string.settings_diag_file_off
            },
            destination = SettingsDestination.TOGGLE_FILE_LOG,
        ),

        // --- 关于 -------------------------------------------------------------------------------
        SettingsEntry(
            id = "about.version",
            group = SettingsGroup.ABOUT,
            titleRes = R.string.settings_about_version,
            summaryRes = R.string.settings_about_version_summary,
            summaryArgs = listOf(facts.appVersion),
            destination = SettingsDestination.NONE,
        ),
        SettingsEntry(
            id = "about.device",
            group = SettingsGroup.ABOUT,
            titleRes = R.string.settings_about_device,
            summaryRes = R.string.settings_about_device_summary,
            summaryArgs = listOf(facts.deviceSummary),
            destination = SettingsDestination.NONE,
        ),
    )

    /**
     * [build]'s rows split into the fixed group order. Empty groups are dropped so the page has no
     * empty header, and a group with no rows cannot be created by [build] at all.
     */
    fun grouped(entries: List<SettingsEntry>): List<Pair<SettingsGroup, List<SettingsEntry>>> =
        GROUP_ORDER.mapNotNull { group ->
            entries.filter { it.group == group }.takeIf { it.isNotEmpty() }?.let { group to it }
        }

    /** `360` → `06:00`; out-of-range values show the same default the scheduler would use. */
    fun formatMinuteOfDay(minuteOfDay: Int): String {
        val safe = minuteOfDay.coerceIn(MINUTE_OF_DAY_RANGE)
        return "%02d:%02d".format(safe / 60, safe % 60)
    }

    private val MINUTE_OF_DAY_RANGE = 0..(24 * 60 - 1)
}
