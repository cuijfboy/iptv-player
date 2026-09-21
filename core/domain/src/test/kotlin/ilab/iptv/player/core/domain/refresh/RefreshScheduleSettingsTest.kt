package ilab.iptv.player.core.domain.refresh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The default is a frozen number (docs/04 P2-5: 每日 06:00) and a corrupt setting cannot move it. */
class RefreshScheduleSettingsTest {

    @Test
    fun `the shipped default is 06 00`() {
        assertThat(RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY).isEqualTo(360)
    }

    @Test
    fun `valid minutes are kept and garbage falls back to the default`() {
        assertThat(RefreshScheduleSettings.sanitize(0)).isEqualTo(0)
        assertThat(RefreshScheduleSettings.sanitize(23 * 60 + 59)).isEqualTo(23 * 60 + 59)
        assertThat(RefreshScheduleSettings.sanitize(-1)).isEqualTo(RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY)
        assertThat(RefreshScheduleSettings.sanitize(24 * 60)).isEqualTo(RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY)
        assertThat(RefreshScheduleSettings.sanitize(99_999)).isEqualTo(RefreshScheduleSettings.DEFAULT_MINUTE_OF_DAY)
    }
}
