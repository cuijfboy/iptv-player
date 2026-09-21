package ilab.iptv.player.core.data.refresh

/**
 * Reads a deep probe's [ilab.iptv.player.core.model.ValidationResult.evidence] map into the fields the
 * `stream` row stores (docs/02 §5.1).
 *
 * The keys are the ones `DeepProbeValidator` writes and `VAL_DEEP_OK` / `VAL_DEEP_FAIL` log (docs/03
 * §3.3): `vcodec`, `acodec`, `w`, `h`, `segStatus`, `ms`. Kept as a tiny, pure mapper so the
 * pipeline's field extraction is unit-tested on its own and a new probe implementation only has to
 * keep that contract instead of depending on this class.
 */
internal data class DeepFields(
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Int,
    val height: Int,
    val costMs: Long?,
)

internal object DeepEvidence {

    const val VIDEO_CODEC = "vcodec"
    const val AUDIO_CODEC = "acodec"
    const val WIDTH = "w"
    const val HEIGHT = "h"
    const val SEGMENT_STATUS = "segStatus"
    const val COST_MS = "ms"

    fun of(evidence: Map<String, Any?>): DeepFields = DeepFields(
        videoCodec = (evidence[VIDEO_CODEC] as? String)?.takeIf { it.isNotBlank() },
        audioCodec = (evidence[AUDIO_CODEC] as? String)?.takeIf { it.isNotBlank() },
        width = intOf(evidence[WIDTH]),
        height = intOf(evidence[HEIGHT]),
        costMs = (evidence[COST_MS] as? Number)?.toLong(),
    )

    private fun intOf(value: Any?): Int = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        else -> 0
    }
}
