package ilab.iptv.player.core.source.validate

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EnvGate
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureOrigin
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
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
 * Stage-one (SHALLOW) reachability probe (docs/02 §6.1): "清单可达 + 直播边沿分片". P2-4a answers
 * only **"can we connect at all"** — a real decode probe is the DEEP stage (P2-4b, Media3).
 *
 * Order of attempts, cheapest first:
 *  1. `HEAD` — one round trip, no body;
 *  2. `GET` with a bounded `Range` — for the many hosts that answer `405`/`403` to `HEAD`.
 *
 * The verdict is [ValidationResult.passed] on any `2xx`/`3xx` answer with the body read (for the GET
 * leg). A transport failure keeps its [AppError] class in the evidence so a `TIMEOUT` is not reported
 * as a dead source. Non-HTTP schemes (`rtsp` / `rtmp` / `udp`) cannot be reached this way and are
 * reported as failures here, with a marker — the DEEP stage/engine is what will handle them.
 */
class ShallowReachabilityValidator(
    private val fetcher: HttpFetcher,
    private val logger: Logger,
    private val limits: PipelineLimits = PipelineLimits(),
) : StreamValidator {

    override val id: String = "shallow.reachability"
    override val order: Int = 10
    override val stage: ValidationStage = ValidationStage.SHALLOW

    override suspend fun validate(target: StreamTarget, ctx: ProbeContext): ValidationResult {
        if (!isHttpScheme(target.url)) {
            return result(
                passed = false,
                detail = "non-http scheme (${schemeOf(target.url)}); shallow HTTP probe cannot reach it",
                evidence = mapOf("scheme" to schemeOf(target.url), "phase" to "unsupported"),
            )
        }
        val timeoutMs = ctx.timeoutMs.takeIf { it > 0 } ?: limits.shallowTimeoutMs

        val head = fetcher.fetch(
            HttpRequest(
                url = target.url,
                method = HttpMethod.HEAD,
                userAgent = target.userAgent,
                referrer = target.referrer,
                timeoutMs = timeoutMs,
                maxBytes = 0,
            ),
        )
        if (head is AppResult.Ok && head.value.status in 200..399) {
            return pass(head.value.status, "head", head.value.elapsedMs, timeoutMs)
        }

        val get = fetcher.fetch(
            HttpRequest(
                url = target.url,
                method = HttpMethod.GET,
                userAgent = target.userAgent,
                referrer = target.referrer,
                timeoutMs = timeoutMs,
                maxBytes = limits.shallowFirstBytes,
                firstBytesOnly = limits.shallowFirstBytes,
            ),
        )
        return when (get) {
            is AppResult.Ok ->
                if (get.value.status in 200..399) {
                    pass(get.value.status, "get", get.value.elapsedMs, timeoutMs)
                } else {
                    result(
                        passed = false,
                        detail = "http ${get.value.status}",
                        evidence = httpEvidence(get.value, timeoutMs, "get"),
                    )
                }
            // GET failed too: prefer the GET failure's class (it is the authoritative leg).
            is AppResult.Err -> result(
                passed = false,
                detail = failureDetail(get.error),
                evidence = mapOf(
                    "failure" to get.error.failure.name,
                    // 只加字段不加码 (docs/05 66): the caller reads this to keep an environment-gate
                    // refusal (418/451/511/605) from being booked as the source going dead.
                    "origin" to get.error.origin.name,
                    "status" to get.error.httpStatus,
                    "timeoutMs" to timeoutMs,
                    "phase" to "get",
                ),
            )
        }
    }

    private fun pass(status: Int, phase: String, elapsedMs: Long, timeoutMs: Long): ValidationResult {
        val evidence = mapOf(
            "status" to status,
            "costMs" to elapsedMs,
            "timeoutMs" to timeoutMs,
            "phase" to phase,
        )
        logger.d(LogCategory.VALIDATE, EventCodes.VAL_SHALLOW_OK, "shallow ok", evidence)
        return ValidationResult(passed = true, detail = "reachable (http $status via $phase)", evidence = evidence)
    }

    private fun result(passed: Boolean, detail: String, evidence: Map<String, Any?>): ValidationResult {
        if (!passed) {
            logger.d(LogCategory.VALIDATE, EventCodes.VAL_SHALLOW_FAIL, "shallow fail", evidence)
        }
        return ValidationResult(passed = passed, detail = detail, evidence = evidence)
    }

    private fun httpEvidence(response: HttpResponse, timeoutMs: Long, phase: String): Map<String, Any?> =
        mapOf(
            "status" to response.status,
            "bytes" to response.bytes.size,
            "costMs" to response.elapsedMs,
            "timeoutMs" to timeoutMs,
            "phase" to phase,
            "origin" to if (EnvGate.isGated(response.status)) FailureOrigin.ENV_GATED.name else FailureOrigin.SOURCE.name,
        )

    private fun failureDetail(error: AppError): String =
        "unreachable (${error.failure.name}${error.httpStatus?.let { " http $it" } ?: ""})"

    private fun isHttpScheme(url: String): Boolean {
        val scheme = schemeOf(url)
        return scheme == "http" || scheme == "https"
    }

    private fun schemeOf(url: String): String =
        url.substringBefore("://", missingDelimiterValue = "").lowercase()
}
