package ilab.iptv.player.core.domain.refresh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Card EPG-SETTINGS-1: the freshness setting is a *setting* now, so its bounds and its cycling are
 * policy, not UI trivia — a corrupted preference must be clamped (requirement 4's `coerceIn`) and the
 * settings page's 确定 press must always land on a valid preset.
 */
class EpgSettingsTest {

    @Test
    fun `the defaults are the frozen ones`() {
        val settings = EpgRefreshSettings()

        assertThat(settings.enabled).isTrue()
        assertThat(settings.minIntervalMs).isEqualTo(EpgRefreshSettings.DEFAULT_MIN_INTERVAL_MS)
        assertThat(settings.emptyRetryMs).isEqualTo(EpgRefreshSettings.DEFAULT_EMPTY_RETRY_MS)
    }

    @Test
    fun `the freshness band is the 30-minute floor and the 6-hour ceiling`() {
        assertThat(EpgRefreshSettings.MIN_INTERVAL_MS).isEqualTo(30 * 60_000L)
        assertThat(EpgRefreshSettings.MAX_INTERVAL_MS).isEqualTo(6 * 60 * 60_000L)
        assertThat(EpgRefreshSettings.sanitizeMinInterval(0L)).isEqualTo(EpgRefreshSettings.MIN_INTERVAL_MS)
        assertThat(EpgRefreshSettings.sanitizeMinInterval(-1L)).isEqualTo(EpgRefreshSettings.MIN_INTERVAL_MS)
        assertThat(EpgRefreshSettings.sanitizeMinInterval(60 * 60_000L)).isEqualTo(60 * 60_000L)
        assertThat(EpgRefreshSettings.sanitizeMinInterval(48 * 60 * 60_000L))
            .isEqualTo(EpgRefreshSettings.MAX_INTERVAL_MS)
    }

    @Test
    fun `sanitized clamps the interval and keeps the empty retry under it`() {
        val clamped = EpgRefreshSettings(minIntervalMs = 1L, emptyRetryMs = 99 * 60 * 60_000L).sanitized()

        assertThat(clamped.minIntervalMs).isEqualTo(EpgRefreshSettings.MIN_INTERVAL_MS)
        // Never longer than `minIntervalMs` — the §6.3 shape the policy would otherwise have to enforce.
        assertThat(clamped.emptyRetryMs).isEqualTo(EpgRefreshSettings.MIN_INTERVAL_MS)
    }

    @Test
    fun `the settings page's 确定 press cycles the presets and wraps`() {
        val presets = EpgRefreshSettings.FRESHNESS_PRESETS_MS
        assertThat(presets.first()).isEqualTo(EpgRefreshSettings.MIN_INTERVAL_MS)
        assertThat(presets.last()).isEqualTo(EpgRefreshSettings.MAX_INTERVAL_MS)

        // Walk the whole list, then wrap back to the first.
        var current = presets.first()
        for (expected in presets.drop(1)) {
            current = EpgRefreshSettings.nextMinInterval(current)
            assertThat(current).isEqualTo(expected)
        }
        assertThat(EpgRefreshSettings.nextMinInterval(current)).isEqualTo(presets.first())
    }

    @Test
    fun `a value that is not a preset cycles to the next preset above it`() {
        // A hand-edited 90 minutes must not cycle back down to 30; it goes to the next preset up (2 h).
        assertThat(EpgRefreshSettings.nextMinInterval(90 * 60_000L)).isEqualTo(2 * 60 * 60_000L)
    }
}
