package ilab.iptv.player.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The settings page's shape, without a device: the group order the remote walks, the destinations the
 * rows lead to, and the §8.1 back-level contract. The screen itself only inflates what this catalog
 * returns, so these are the assertions that keep the page honest.
 */
class SettingsCatalogTest {

    private fun facts(
        sourceCount: Int = 0,
        enabledSourceCount: Int = 0,
        refreshMinuteOfDay: Int = 360,
        playbackAvoidanceEnabled: Boolean = true,
        logLevel: String = "INFO",
        fileLogEnabled: Boolean = true,
        epgEnabled: Boolean = true,
        epgMinIntervalMs: Long = 6 * 60 * 60_000L,
        lastRefreshSummary: String = "还没刷新过",
    ) = SettingsFacts(
        sourceCount = sourceCount,
        enabledSourceCount = enabledSourceCount,
        lastRefreshSummary = lastRefreshSummary,
        refreshMinuteOfDay = refreshMinuteOfDay,
        playbackAvoidanceEnabled = playbackAvoidanceEnabled,
        logLevel = logLevel,
        fileLogEnabled = fileLogEnabled,
        epgEnabled = epgEnabled,
        epgMinIntervalMs = epgMinIntervalMs,
        appVersion = "0.5.2 (5)",
        deviceSummary = "Sony BRAVIA / Android 12 (API 31) / ABI arm64-v8a",
    )

    @Test
    fun `groups appear in the frozen order and every group has rows`() {
        val grouped = SettingsCatalog.grouped(SettingsCatalog.build(facts()))

        assertThat(grouped.map { it.first }).containsExactly(
            SettingsGroup.SOURCE,
            SettingsGroup.PLAYBACK,
            SettingsGroup.REFRESH,
            SettingsGroup.DIAGNOSTICS,
            SettingsGroup.ABOUT,
        ).inOrder()
        assertThat(grouped).hasSize(SettingsCatalog.GROUP_ORDER.size)
        assertThat(grouped.all { it.second.isNotEmpty() }).isTrue()
    }

    @Test
    fun `the rows that lead somewhere are exactly the ones the dispatch asks for`() {
        val entries = SettingsCatalog.build(facts())

        assertThat(entries.filter { it.destination != SettingsDestination.NONE }.map { it.id })
            .containsExactly(
                "source.manage",
                "refresh.epg.enabled",
                "refresh.epg.freshness",
                "diag.panel",
                "diag.console",
                "diag.level",
                "diag.file",
            )
        // Every navigable row must be usable: a disabled row the remote cannot reach is a dead end.
        assertThat(entries.filter { it.destination != SettingsDestination.NONE }.all { it.enabled }).isTrue()
    }

    @Test
    fun `the value rows report what the app is actually doing`() {
        val entries = SettingsCatalog.build(facts(logLevel = "DEBUG", fileLogEnabled = false))

        assertThat(entries.first { it.id == "diag.level" }.summaryArgs).containsExactly("DEBUG")
        assertThat(entries.first { it.id == "diag.file" }.summaryRes)
            .isEqualTo(R.string.settings_diag_file_off)
        assertThat(SettingsCatalog.build(facts(fileLogEnabled = true)).first { it.id == "diag.file" }.summaryRes)
            .isEqualTo(R.string.settings_diag_file_on)
    }

    @Test
    fun `the EPG rows report the switch and the freshness threshold`() {
        val on = SettingsCatalog.build(facts(epgEnabled = true))
        assertThat(on.first { it.id == "refresh.epg.enabled" }.summaryRes)
            .isEqualTo(R.string.settings_epg_enabled_on)
        assertThat(SettingsCatalog.build(facts(epgEnabled = false)).first { it.id == "refresh.epg.enabled" }.summaryRes)
            .isEqualTo(R.string.settings_epg_enabled_off)

        // The freshness row shows a person-readable interval, not milliseconds.
        assertThat(SettingsCatalog.build(facts(epgMinIntervalMs = 60 * 60_000L))
            .first { it.id == "refresh.epg.freshness" }.summaryArgs)
            .containsExactly("1 小时")
        assertThat(SettingsCatalog.build(facts(epgMinIntervalMs = 30 * 60_000L))
            .first { it.id == "refresh.epg.freshness" }.summaryArgs)
            .containsExactly("30 分钟")
    }

    @Test
    fun `formatInterval reads hours for whole hours and minutes otherwise`() {
        assertThat(SettingsCatalog.formatInterval(6 * 60 * 60_000L)).isEqualTo("6 小时")
        assertThat(SettingsCatalog.formatInterval(2 * 60 * 60_000L)).isEqualTo("2 小时")
        assertThat(SettingsCatalog.formatInterval(30 * 60_000L)).isEqualTo("30 分钟")
        assertThat(SettingsCatalog.formatInterval(90 * 60_000L)).isEqualTo("90 分钟")
    }

    @Test
    fun `the back level is settings to browse and diagnostics to settings`() {
        assertThat(SettingsHierarchy.parentOf(SettingsScreen.SETTINGS)).isEqualTo(SettingsScreen.BROWSE)
        assertThat(SettingsHierarchy.parentOf(SettingsScreen.DIAGNOSTICS)).isEqualTo(SettingsScreen.SETTINGS)
        assertThat(SettingsHierarchy.parentOf(SettingsScreen.BROWSE)).isNull()
    }

    @Test
    fun `minute of day renders as a clock and falls back when out of range`() {
        assertThat(SettingsCatalog.formatMinuteOfDay(360)).isEqualTo("06:00")
        assertThat(SettingsCatalog.formatMinuteOfDay(0)).isEqualTo("00:00")
        assertThat(SettingsCatalog.formatMinuteOfDay(23 * 60 + 59)).isEqualTo("23:59")
        // The value normally arrives already sanitized from `RefreshScheduleSettings`; this guard is
        // only here so a corrupted setting cannot render as `25:99` on the page.
        assertThat(SettingsCatalog.formatMinuteOfDay(24 * 60)).isEqualTo("23:59")
        assertThat(SettingsCatalog.formatMinuteOfDay(-5)).isEqualTo("00:00")
    }
}
