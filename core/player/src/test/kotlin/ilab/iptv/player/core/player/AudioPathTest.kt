package ilab.iptv.player.core.player

import androidx.media3.common.MimeTypes
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** docs/02 §7.6: "引擎/设备解码为 PCM" vs "码流直通 HAL", as measured in S2. */
class AudioPathTest {

    @Test
    fun `compressed formats go to the HAL untouched`() {
        assertThat(AudioPathClassifier.of(MimeTypes.AUDIO_AC3)).isEqualTo(AudioPath.PASSTHROUGH_HAL)
        assertThat(AudioPathClassifier.of(MimeTypes.AUDIO_E_AC3)).isEqualTo(AudioPath.PASSTHROUGH_HAL)
        assertThat(AudioPathClassifier.of(MimeTypes.AUDIO_DTS)).isEqualTo(AudioPath.PASSTHROUGH_HAL)
    }

    @Test
    fun `aac and mp2 are decoded to pcm first`() {
        assertThat(AudioPathClassifier.of(MimeTypes.AUDIO_AAC)).isEqualTo(AudioPath.PCM_DECODED)
        assertThat(AudioPathClassifier.of(MimeTypes.AUDIO_MPEG)).isEqualTo(AudioPath.PCM_DECODED)
        // S2: media3 1.6 reports MP2 as `audio/mpeg-L2` — not `audio/mpeg` — and it is PCM-decoded.
        assertThat(AudioPathClassifier.of(MimeTypes.AUDIO_MPEG_L2)).isEqualTo(AudioPath.PCM_DECODED)
    }

    @Test
    fun `no audio track means no audio path`() {
        assertThat(AudioPathClassifier.of(null)).isEqualTo(AudioPath.NONE)
    }
}
