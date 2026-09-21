package ilab.iptv.player.core.domain.scoring

import ilab.iptv.player.core.model.CodecIds
import ilab.iptv.player.core.model.ScoreInput
import kotlin.math.roundToInt

/**
 * The docs/02 §6.1 scoring table, one rule per dimension (docs/02 §4.3 `ScoringRule`).
 *
 * Weights are the document's numbers, unmodified: 可用性 35 / 稳定性 25 / 设备兼容 20 / 画质码率 10 /
 * 防盗链 5 / 分片成功 2. **They sum to 97, not 100** — that is a docs/02 §6.1 arithmetic gap the
 * P2-4b report raises with arch; the rules keep the document's points and `DefaultScorer` normalizes
 * the sum to the 0..100 the same document asks for (§8.4 E6), so the selection thresholds are read on
 * one scale.
 *
 * A rule never returns more than its weight: `evaluate` may subtract (stability, device penalties),
 * so the result is clamped by [DefaultScorer].
 */
/** Weight values as written in docs/02 §6.1. */
object ScoringWeights {
    const val AVAILABILITY = 35
    const val STABILITY = 25
    const val DEVICE_COMPAT = 20
    const val RESOLUTION = 10
    const val ANTI_LEECH = 5
    const val SEGMENT = 2

    /** 97 — see the class KDoc: the document's table does not add up to 100. */
    val TOTAL: Int = AVAILABILITY + STABILITY + DEVICE_COMPAT + RESOLUTION + ANTI_LEECH + SEGMENT
}

/**
 * 可用性 35 (docs/02 §6.1): "判定可播放即得". The deep probe is the only thing that decides this,
 * which is why an unprobed or failed stream scores 0 here and can never out-rank a verified one.
 */
class AvailabilityRule : ScoringRule {
    override val id: String = "availability"
    override val weight: Int = ScoringWeights.AVAILABILITY
    override fun evaluate(input: ScoreInput): Int = if (input.probe.passed) weight else 0
}

/**
 * 稳定性 25 (docs/02 §6.1): "按轮询失败率线性扣；媒体序号不前进再扣 10".
 *
 * [ScoreInput.stability] is the 0..1 health factor the caller derived (`Stability`); this rule just
 * scales it. The "媒体序号不前进" (a live playlist that stopped advancing) observation, when the
 * probe saw it, is a flat −10 — it is a different failure mode from a poll failure and the document
 * prices it separately.
 */
class StabilityRule : ScoringRule {
    override val id: String = "stability"
    override val weight: Int = ScoringWeights.STABILITY

    override fun evaluate(input: ScoreInput): Int {
        val scaled = (weight * input.stability.coerceIn(0.0, 1.0)).roundToInt()
        val stalled = input.probe.evidence[EVIDENCE_STALLED] == true
        return (scaled - if (stalled) STALL_PENALTY else 0).coerceAtLeast(0)
    }

    private companion object {
        const val STALL_PENALTY = 10
        const val EVIDENCE_STALLED = "stalled"
    }
}

/**
 * 设备兼容 20 (docs/02 §6.1): "H.264=20；H.265=12；AV1=4；AC3/EAC3 −5；>1080p −3".
 *
 * The base score is the **video family**; unknown codecs get the middle value (12) rather than a
 * free 20 or a punitive 0 — the pipeline could not tell, so it must not pretend either way. That
 * choice is a口径 decision recorded in the P2-4b report. AC3/EAC3 is only penalized when the device
 * does **not** list the format in `DeviceProfile.audioPassthrough` (docs/02 §4.7: the profile is
 * probed, never hard-coded per model).
 */
class DeviceCompatibilityRule : ScoringRule {
    override val id: String = "device_compat"
    override val weight: Int = ScoringWeights.DEVICE_COMPAT

    override fun evaluate(input: ScoreInput): Int {
        var points = when (CodecIds.videoFamily(input.videoCodec)) {
            CodecIds.VideoFamily.H264 -> weight
            CodecIds.VideoFamily.HEVC -> 12
            CodecIds.VideoFamily.AV1 -> 4
            CodecIds.VideoFamily.UNKNOWN -> UNKNOWN_CODEC_POINTS
            CodecIds.VideoFamily.OTHER -> UNKNOWN_CODEC_POINTS
        }
        if (!audioIsPassedThrough(input)) points -= AC3_PENALTY
        if (exceedsDeviceResolution(input)) points -= OVER_RESOLUTION_PENALTY
        return points.coerceAtLeast(0)
    }

    private fun audioIsPassedThrough(input: ScoreInput): Boolean = when (CodecIds.audioFamily(input.audioCodec)) {
        CodecIds.AudioFamily.AC3 -> input.device.audioPassthrough.any { it.equals("ac3", ignoreCase = true) }
        CodecIds.AudioFamily.EAC3 -> input.device.audioPassthrough.any { it.equals("eac3", ignoreCase = true) }
        else -> true
    }

    /** Either the device cannot do this resolution, or it is simply above the 1080p reference. */
    private fun exceedsDeviceResolution(input: ScoreInput): Boolean {
        val width = input.width
        val height = input.height
        if (width <= 0 && height <= 0) return false
        val beyondDevice = (width > 0 && width > input.device.maxWidth) ||
            (height > 0 && height > input.device.maxHeight)
        val beyond1080p = width > 1_920 || height > 1_080
        return beyondDevice || beyond1080p
    }

    private companion object {
        const val UNKNOWN_CODEC_POINTS = 12
        const val AC3_PENALTY = 5
        const val OVER_RESOLUTION_PENALTY = 3
    }
}

/**
 * 画质/码率 10 (docs/02 §6.1): "1080p=10；720p=8；480p=5".
 *
 * The document only prices three tiers, so 4K and above score with 1080p (the device penalty in
 * [DeviceCompatibilityRule] is what charges for going above the reference resolution) and a stream
 * with no measured resolution gets 0 — an unseen resolution is not a claim we can credit.
 */
class ResolutionRule : ScoringRule {
    override val id: String = "resolution"
    override val weight: Int = ScoringWeights.RESOLUTION

    override fun evaluate(input: ScoreInput): Int {
        val height = input.height
        val width = input.width
        return when {
            height >= 1_080 || width >= 1_920 -> 10
            height >= 720 || width >= 1_280 -> 8
            height >= 480 || width >= 640 -> 5
            else -> 0
        }
    }
}

/**
 * 防盗链 5 (docs/02 §6.1): "无需特殊头=5；需要则 3（并记录 UA/Referer）". The UA/Referer are
 * already on the stream row (docs/02 §5.1) and are what a playback request replays.
 */
class AntiLeechRule : ScoringRule {
    override val id: String = "anti_leech"
    override val weight: Int = ScoringWeights.ANTI_LEECH

    override fun evaluate(input: ScoreInput): Int {
        val needsHeaders = !input.stream.userAgent.isNullOrBlank() || !input.stream.referrer.isNullOrBlank()
        return if (needsHeaders) 3 else weight
    }
}

/**
 * 分片成功 2 (docs/02 §6.1): "边沿分片 2xx 加分". Only the deep probe's segment leg can award this;
 * a stream the run did not probe (fresh, skipped) has no segment evidence and scores 0 here.
 */
class SegmentSuccessRule : ScoringRule {
    override val id: String = "segment"
    override val weight: Int = ScoringWeights.SEGMENT

    override fun evaluate(input: ScoreInput): Int {
        val status = input.probe.evidence[EVIDENCE_SEGMENT_STATUS] as? Int ?: return 0
        return if (status in 200..299) weight else 0
    }

    private companion object {
        const val EVIDENCE_SEGMENT_STATUS = "segStatus"
    }
}

/** The rules in the order docs/02 §6.1 lists them (the order `SRC_SCORE` reports them in). */
object DefaultScoringRules {
    fun all(): List<ScoringRule> = listOf(
        AvailabilityRule(),
        StabilityRule(),
        DeviceCompatibilityRule(),
        ResolutionRule(),
        AntiLeechRule(),
        SegmentSuccessRule(),
    )
}
