package ilab.iptv.player.core.domain.scoring

import ilab.iptv.player.core.model.DeviceProfile
import ilab.iptv.player.core.model.ScoreInput
import ilab.iptv.player.core.model.Stream
import ilab.iptv.player.core.model.ValidationResult

/**
 * The device docs/02 §6.1's 设备兼容 rule is measured against: 1080p, no audio passthrough — the
 * shape S1/S2 filled in (docs/02 §4.7 asks for values, not a model name).
 */
val DEVICE_1080P_NO_PASSTHROUGH = DeviceProfile(
    abi = "arm64-v8a",
    sdk = 30,
    ramMb = 2048,
    audioPassthrough = emptySet(),
    maxWidth = 1920,
    maxHeight = 1080,
    maxFrameRate = 60f,
)

/** A stream row with the fields the scoring rules read. Named apart from the playback fixture. */
fun scoreStream(
    id: Long = 1,
    score: Int = 0,
    userAgent: String? = null,
    referrer: String? = null,
): Stream = Stream(
    id = id,
    channelId = 1,
    url = "http://example.invalid/$id.m3u8",
    userAgent = userAgent,
    referrer = referrer,
    sourceId = "src-a",
    quality = null,
    videoCodec = null,
    audioCodec = null,
    width = 1920,
    height = 1080,
    score = score,
    priority = 0,
    lastOkAtMs = null,
    lastCheckAtMs = null,
    failCount = 0,
    lastError = null,
    disabled = false,
)

fun probe(
    passed: Boolean = true,
    evidence: Map<String, Any?> = emptyMap(),
    detail: String = "probe",
): ValidationResult = ValidationResult(passed = passed, detail = detail, evidence = evidence)

fun scoreInput(
    stream: Stream = scoreStream(),
    probe: ValidationResult = probe(),
    videoCodec: String? = "video/avc",
    audioCodec: String? = "audio/mp4a-latm",
    width: Int = 1920,
    height: Int = 1080,
    stability: Double = 1.0,
    device: DeviceProfile = DEVICE_1080P_NO_PASSTHROUGH,
): ScoreInput = ScoreInput(
    stream = stream,
    probe = probe,
    videoCodec = videoCodec,
    audioCodec = audioCodec,
    width = width,
    height = height,
    stability = stability,
    device = device,
)
