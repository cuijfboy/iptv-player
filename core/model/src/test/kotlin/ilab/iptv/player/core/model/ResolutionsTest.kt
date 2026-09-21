package ilab.iptv.player.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ResolutionsTest {

    @Test
    fun `resolution maps to the quality tiers the player labels`() {
        assertThat(Resolutions.qualityOf(3840, 2160)).isEqualTo(Quality.UHD_4K)
        assertThat(Resolutions.qualityOf(1920, 1080)).isEqualTo(Quality.FHD_1080)
        assertThat(Resolutions.qualityOf(1280, 720)).isEqualTo(Quality.HD_720)
        assertThat(Resolutions.qualityOf(854, 480)).isEqualTo(Quality.SD)
    }

    @Test
    fun `a missing resolution is unknown, not SD`() {
        assertThat(Resolutions.qualityOf(0, 0)).isEqualTo(Quality.UNKNOWN)
    }

    @Test
    fun `a width-only reading still classifies`() {
        assertThat(Resolutions.qualityOf(1920, 0)).isEqualTo(Quality.FHD_1080)
        assertThat(Resolutions.qualityOf(0, 720)).isEqualTo(Quality.HD_720)
        assertThat(Resolutions.qualityOf(320, 0)).isEqualTo(Quality.SD)
    }
}
