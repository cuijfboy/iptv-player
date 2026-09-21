package ilab.iptv.player.core.source.deep

import ilab.iptv.player.core.model.StreamTarget

/**
 * The seam between the deep probe's **network** leg and a real decoder (docs/02 §6.1 Deep:
 * "Media3 `Probe` 解出真实 vcodec/acodec/分辨率").
 *
 * Why a seam instead of a direct Media3 call: `:core:source` may not depend on `:core:player`
 * (docs/02 §3.2 matrix — `source` row has no `player` column), and a decode needs a device. So the
 * engine-backed implementation binds here from `:core:player` when it lands; until then the deep
 * probe reports the codecs the **playlist declares** and marks them
 * `codecSource = "declared"` instead of pretending they were decoded.
 *
 * The P2-4b report tracks this as the remaining device-round item.
 */
fun interface TrackProbe {
    /**
     * Opens [target] just far enough to read its tracks, then releases. Must not play for a
     * meaningful time (派单: "不做长时间播放") and must answer — never throw — on failure.
     */
    suspend fun probe(target: StreamTarget, timeoutMs: Long): TrackProbeResult
}

/**
 * What a real decode found. Codec values use the Media3/Android MIME names, the same ones
 * `stream.vcodec` / `stream.acodec` hold (docs/02 §5.1), so the scorer's family mapping
 * (`CodecIds`) sees one vocabulary.
 */
data class TrackProbeResult(
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Int,
    val height: Int,
    val detail: String,
)
