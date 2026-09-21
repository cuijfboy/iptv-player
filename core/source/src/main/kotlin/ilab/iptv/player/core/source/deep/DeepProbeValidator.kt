package ilab.iptv.player.core.source.deep

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.model.CodecIds
import ilab.iptv.player.core.model.ProbeContext
import ilab.iptv.player.core.model.StreamTarget
import ilab.iptv.player.core.model.ValidationResult
import ilab.iptv.player.core.model.ValidationStage
import ilab.iptv.player.core.network.HttpFetcher
import ilab.iptv.player.core.network.HttpMethod
import ilab.iptv.player.core.network.HttpRequest
import ilab.iptv.player.core.network.HttpResponse
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.provider.StreamValidator

/**
 * Stage-two (DEEP) probe of docs/02 §6.1: "Media3 `Probe` 解出真实 vcodec/acodec/分辨率", without
 * the "长时间播放" the 派单 forbids.
 *
 * What it actually does, in order:
 *  1. fetch the stream URL (bounded) — this is also the "取到字节" evidence for a direct TS stream;
 *  2. when the body is an HLS playlist, walk **master → first variant → media playlist → edge
 *     segment (末片, falling back to 首片)** and fetch that segment — the "能拉到分片" leg;
 *  3. read the codecs/resolution the playlist **declares** (`#EXT-X-STREAM-INF:CODECS`/`RESOLUTION`);
 *  4. if a [TrackProbe] is bound, let a real decoder override 2's/3's readings and mark
 *     `codecSource = "probe"`.
 *
 * The verdict is about bytes, not about quality: a 2xx playlist plus a 2xx segment with a body is a
 * pass. Everything the scorer needs travels in [ValidationResult.evidence] under fixed keys
 * (`vcodec`, `acodec`, `w`, `h`, `segStatus`, `segBytes`, `variant`, `codecSource`, `ms`), which is
 * also exactly what `VAL_DEEP_OK` / `VAL_DEEP_FAIL` log (docs/03 §3.3).
 *
 * Non-HTTP schemes (`rtsp`/`rtmp`/`udp`) cannot be probed here — the shallow stage already reports
 * them as unsupported and this stage repeats that honestly instead of inventing a verdict.
 */
class DeepProbeValidator(
    private val fetcher: HttpFetcher,
    private val logger: Logger,
    private val limits: PipelineLimits = PipelineLimits(),
    private val trackProbe: TrackProbe? = null,
) : StreamValidator {

    override val id: String = "deep.probe"
    override val order: Int = 20
    override val stage: ValidationStage = ValidationStage.DEEP

    override suspend fun validate(target: StreamTarget, ctx: ProbeContext): ValidationResult {
        val scheme = schemeOf(target.url)
        if (scheme != "http" && scheme != "https") {
            return fail(
                detail = "non-http scheme ($scheme); deep probe needs the engine/Media3 (P2-4b device round)",
                evidence = mapOf("scheme" to scheme, "phase" to "unsupported", "codecSource" to "unknown"),
            )
        }
        val timeoutMs = ctx.timeoutMs.takeIf { it > 0 } ?: limits.deepTimeoutMs

        val playlist = fetcher.fetch(
            HttpRequest(
                url = target.url,
                method = HttpMethod.GET,
                userAgent = target.userAgent,
                referrer = target.referrer,
                timeoutMs = timeoutMs,
                maxBytes = limits.deepFirstBytes,
            ),
        )
        val body = when (playlist) {
            is AppResult.Err -> return failFromError(playlist.error, "playlist")
            is AppResult.Ok -> playlist.value
        }
        if (body.status !in 200..399) {
            return fail(
                detail = "playlist http ${body.status}",
                evidence = baseEvidence(body, timeoutMs, phase = "playlist") + ("failure" to FailureClass.HTTP_CLIENT.name),
            )
        }

        val declared = HlsManifestParser.parse(String(body.bytes, Charsets.UTF_8), target.url)
        val streaming = declared?.let { walk(target, it, timeoutMs) }
        val decode = streaming ?: directPull(body)

        val probed = if (trackProbe != null) {
            val result = trackProbe.probe(target, timeoutMs)
            decode.copy(
                videoCodec = result.videoCodec ?: decode.videoCodec,
                audioCodec = result.audioCodec ?: decode.audioCodec,
                width = result.width.takeIf { it > 0 } ?: decode.width,
                height = result.height.takeIf { it > 0 } ?: decode.height,
                codecSource = "probe",
                detail = "${decode.detail}; probe ${result.detail}",
            )
        } else {
            decode
        }

        val evidence = probed.evidence(timeoutMs)
        if (!probed.passed) {
            logger.d(LogCategory.VALIDATE, EventCodes.VAL_DEEP_FAIL, "deep fail", evidence)
            return ValidationResult(passed = false, detail = probed.detail, evidence = evidence)
        }
        logger.d(LogCategory.VALIDATE, EventCodes.VAL_DEEP_OK, "deep ok", evidence)
        return ValidationResult(passed = true, detail = probed.detail, evidence = evidence)
    }

    // --- HLS: master → variant → edge segment ------------------------------------------------------

    private suspend fun walk(target: StreamTarget, manifest: HlsManifest, timeoutMs: Long): Probe {
        var variant: HlsVariant? = null
        var segments = manifest.segments

        if (manifest.isMaster) {
            val first = manifest.variants.firstOrNull()
                ?: return Probe.failed("master playlist declares no variant", FailureClass.EMPTY_MEDIA)
            val variantFetch = fetchPlaylist(target, first.uri, timeoutMs)
            when (variantFetch) {
                is AppResult.Err -> return Probe.fromError(variantFetch.error, "variant")
                is AppResult.Ok -> {
                    if (variantFetch.value.status !in 200..399) {
                        return Probe.failed(
                            "variant http ${variantFetch.value.status}",
                            FailureClass.HTTP_CLIENT,
                            mapOf("status" to variantFetch.value.status, "phase" to "variant"),
                        )
                    }
                    variant = first
                    segments = HlsManifestParser
                        .parse(String(variantFetch.value.bytes, Charsets.UTF_8), first.uri)
                        ?.segments
                        ?: emptyList()
                }
            }
        }

        val ordered = liveEdgeOrder(segments)
        if (ordered.isEmpty()) {
            return Probe.failed("playlist declares no segment", FailureClass.EMPTY_MEDIA)
        }

        var lastFailure: Probe? = null
        for ((index, segment) in ordered.withIndex()) {
            val fetch = fetcher.fetch(
                HttpRequest(
                    url = segment,
                    method = HttpMethod.GET,
                    userAgent = target.userAgent,
                    referrer = target.referrer,
                    timeoutMs = timeoutMs,
                    maxBytes = limits.deepFirstBytes,
                    firstBytesOnly = limits.deepFirstBytes,
                ),
            )
            when (fetch) {
                is AppResult.Err -> lastFailure = Probe.fromError(fetch.error, "segment")
                is AppResult.Ok -> {
                    val response = fetch.value
                    if (response.status in 200..299 && response.bytes.isNotEmpty()) {
                        val codecs = variant?.codecs ?: emptyList()
                        return Probe(
                            passed = true,
                            detail = "edge segment ${response.status}, ${response.bytes.size} B" +
                                (if (variant != null) " (variant ${variant.bandwidth ?: 0} bps)" else ""),
                            videoCodec = videoMime(codecs),
                            audioCodec = audioMime(codecs),
                            width = variant?.width ?: 0,
                            height = variant?.height ?: 0,
                            codecSource = if (codecs.isEmpty()) "unknown" else "declared",
                            segmentStatus = response.status,
                            segmentBytes = response.bytes.size,
                            costMs = response.elapsedMs,
                            variantUrl = variant?.uri,
                            segmentCount = segments.size,
                            phase = if (index == 0) "edge" else "first",
                        )
                    }
                    lastFailure = Probe.failed(
                        "segment http ${response.status}",
                        if (response.status >= 500) FailureClass.HTTP_SERVER else FailureClass.HTTP_CLIENT,
                        mapOf("status" to response.status, "phase" to "segment"),
                    )
                }
            }
        }
        return lastFailure ?: Probe.failed("segment fetch failed", FailureClass.UNKNOWN)
    }

    /** Index of the live edge first (docs/02 §6.1 "末片→首片"), then the playlist head. */
    private fun liveEdgeOrder(segments: List<String>): List<String> {
        val last = segments.lastOrNull()
        val first = segments.firstOrNull()
        return when {
            last == null -> emptyList()
            first == null || last == first -> listOf(last)
            else -> listOf(last, first)
        }
    }

    // --- Direct (non-playlist) media: the fetch itself is the byte pull ----------------------------

    private fun directPull(body: HttpResponse): Probe {
        val bytes = body.bytes.size
        val ok = body.status in 200..299 && bytes > 0
        return Probe(
            passed = ok,
            detail = if (ok) {
                "direct media ${body.status}, $bytes B"
            } else {
                "direct media http ${body.status}, $bytes B"
            },
            videoCodec = null,
            audioCodec = null,
            width = 0,
            height = 0,
            codecSource = "unknown",
            segmentStatus = body.status,
            segmentBytes = bytes,
            costMs = body.elapsedMs,
            variantUrl = null,
            segmentCount = 1,
            phase = "direct",
        ).let { if (ok) it else it.copy(failure = FailureClass.HTTP_CLIENT) }
    }

    private suspend fun fetchPlaylist(target: StreamTarget, url: String, timeoutMs: Long): AppResult<HttpResponse> =
        fetcher.fetch(
            HttpRequest(
                url = url,
                method = HttpMethod.GET,
                userAgent = target.userAgent,
                referrer = target.referrer,
                timeoutMs = timeoutMs,
                maxBytes = limits.deepFirstBytes,
            ),
        )

    // --- Evidence / logging -----------------------------------------------------------------------

    private fun fail(detail: String, evidence: Map<String, Any?>): ValidationResult {
        logger.d(LogCategory.VALIDATE, EventCodes.VAL_DEEP_FAIL, "deep fail", evidence)
        return ValidationResult(passed = false, detail = detail, evidence = evidence)
    }

    private fun failFromError(error: AppError, phase: String): ValidationResult = fail(
        detail = "unreachable (${error.failure.name}${error.httpStatus?.let { " http $it" } ?: ""})",
        evidence = mapOf(
            "failure" to error.failure.name,
            "status" to error.httpStatus,
            "phase" to phase,
            "codecSource" to "unknown",
        ),
    )

    private fun baseEvidence(response: HttpResponse, timeoutMs: Long, phase: String): Map<String, Any?> = mapOf(
        "status" to response.status,
        "bytes" to response.bytes.size,
        "costMs" to response.elapsedMs,
        "timeoutMs" to timeoutMs,
        "phase" to phase,
    )

    private fun videoMime(codecs: List<String>): String? =
        codecs.firstNotNullOfOrNull { CodecIds.videoMime(it) }

    private fun audioMime(codecs: List<String>): String? =
        codecs.firstNotNullOfOrNull { CodecIds.audioMime(it) }

    private fun schemeOf(url: String): String =
        url.substringBefore("://", missingDelimiterValue = "").lowercase()

    /** One probe's outcome, the internal carrier between the three legs and the evidence map. */
    private data class Probe(
        val passed: Boolean,
        val detail: String,
        val videoCodec: String?,
        val audioCodec: String?,
        val width: Int,
        val height: Int,
        val codecSource: String,
        val segmentStatus: Int?,
        val segmentBytes: Int,
        val costMs: Long?,
        val variantUrl: String?,
        val segmentCount: Int,
        val phase: String,
        val failure: FailureClass? = null,
    ) {
        fun evidence(timeoutMs: Long): Map<String, Any?> = buildMap {
            put("phase", phase)
            put("status", segmentStatus)
            put("segStatus", segmentStatus)
            put("segBytes", segmentBytes)
            put("segments", segmentCount)
            put("variant", variantUrl)
            put("vcodec", videoCodec)
            put("acodec", audioCodec)
            put("w", width)
            put("h", height)
            put("codecSource", codecSource)
            put("ms", costMs)
            put("timeoutMs", timeoutMs)
            failure?.let { put("failure", it.name) }
        }

        companion object {
            fun failed(
                detail: String,
                failure: FailureClass,
                extra: Map<String, Any?> = emptyMap(),
            ): Probe = Probe(
                passed = false,
                detail = detail,
                videoCodec = null,
                audioCodec = null,
                width = 0,
                height = 0,
                codecSource = "unknown",
                segmentStatus = extra["status"] as? Int,
                segmentBytes = 0,
                costMs = null,
                variantUrl = null,
                segmentCount = 0,
                phase = extra["phase"] as? String ?: "unknown",
                failure = failure,
            )

            fun fromError(error: AppError, phase: String): Probe = failed(
                detail = "unreachable (${error.failure.name})",
                failure = error.failure,
                extra = mapOf("phase" to phase, "status" to error.httpStatus),
            )
        }
    }
}
