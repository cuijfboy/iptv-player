package ilab.iptv.player.core.player

import androidx.media3.common.MimeTypes

/**
 * Which of the two audio paths of docs/02 §7.6 the current stream is on. S2 measured that AC3/EAC3
 * reach the HAL as a COMPRESSED bitstream (device decodes it) while AAC/MP2 are decoded to PCM first;
 * the two names are the revised §7.6 wording ("引擎/设备解码为 PCM" vs "码流直通 HAL").
 */
enum class AudioPath { PCM_DECODED, PASSTHROUGH_HAL, NONE }

object AudioPathClassifier {

    /** Mime types the platform hands to the HAL untouched (S2: `AUDIO_FORMAT_AC3`/`_E_AC3` DIRECT). */
    private val PASSTHROUGH_MIMES = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_TRUEHD,
    )

    fun of(audioMime: String?): AudioPath = when {
        audioMime == null -> AudioPath.NONE
        audioMime in PASSTHROUGH_MIMES -> AudioPath.PASSTHROUGH_HAL
        else -> AudioPath.PCM_DECODED
    }
}
