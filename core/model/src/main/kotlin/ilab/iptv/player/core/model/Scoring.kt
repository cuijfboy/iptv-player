package ilab.iptv.player.core.model

/**
 * Scoring and selection transfer model (docs/02 §4.2 / §6.1, P2-4b).
 *
 * Both are **pure policy inputs**: a `Scorer` (docs/02 §4.3) turns one [ScoreInput] into a
 * [ScoreBreakdown] and a `StreamSelector` ranks a channel's [Stream]s, with no Android and no I/O —
 * so the whole "which stream is better" decision is unit-testable off device (docs/02 §1).
 */

/**
 * Everything one stream is scored on (docs/02 §4.2, one documented deviation).
 *
 * **Deviation from §4.2 (请 arch 回写)**：the doc sketch carries `entry: RawEntry`, but after P2-1
 * the scored object is a persisted [Stream] — a `RawEntry` has no id, no health and no probe
 * results, so it cannot be the subject of a score. The field is therefore `stream: Stream`; every
 * other field keeps the doc's name and meaning. The codec/resolution fields are the **probe's**
 * readings for this run (they may be fresher than the row's stored values); [stability] is the
 * normalized 0..1 health factor (see `Stability` in `:core:domain`), into which docs/02 §6.1
 * folds the source-side startup cost (W-S1-2).
 */
data class ScoreInput(
    val stream: Stream,
    val probe: ValidationResult,
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Int,
    val height: Int,
    val stability: Double,
    val device: DeviceProfile,
)

/**
 * One stream's score (docs/02 §4.2). [total] is normalized to **0..100** (docs/02 §8.4 E6: "总分
 * 归一化到 100") so the §6.1 selection thresholds are on one scale; [byRule] keeps the raw points
 * each rule awarded, keyed by rule id, which is exactly what `SRC_SCORE` logs.
 */
data class ScoreBreakdown(val total: Int, val byRule: Map<String, Int>)

/**
 * Rank input for one channel (docs/02 §4.3, frozen shape).
 *
 * Ranking itself uses only the frozen key (`score desc → priority asc → lastOkAtMs desc → id asc`);
 * [engineCaps] and [device] are carried for a future capability gate (an engine that cannot play a
 * transport must not be handed one) and [health] for a future health-aware re-rank.
 */
data class SelectionInput(
    val channel: Channel,
    val candidates: List<Stream>,
    val nowMs: Long,
    val engineCaps: Set<EngineCapability>,
    val device: DeviceProfile,
    val health: Map<Long, StreamHealth>,
)

/**
 * Resolution → [Quality] mapping (docs/02 §4.2 `Quality`, §6.1 画质/码率 dimension).
 *
 * Kept here (not in the scorer) because it is also what the refresh pipeline writes into
 * `stream.quality` when the deep probe reports a resolution.
 */
object Resolutions {

    /** 1080p and up counts as [Quality.FHD_1080]; 4K is called out only at ≥2160. */
    fun qualityOf(width: Int, height: Int): Quality = when {
        height >= 2_160 || width >= 3_840 -> Quality.UHD_4K
        height >= 1_080 || width >= 1_920 -> Quality.FHD_1080
        height >= 720 || width >= 1_280 -> Quality.HD_720
        height > 0 || width > 0 -> Quality.SD
        else -> Quality.UNKNOWN
    }
}
