package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.EngineCapability
import ilab.iptv.player.core.model.ProbeContext
import ilab.iptv.player.core.model.StreamTarget
import ilab.iptv.player.core.model.ValidationStage
import ilab.iptv.player.core.network.HttpMethod
import ilab.iptv.player.core.network.HttpRequest
import ilab.iptv.player.core.network.HttpResponse
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.validate.ShallowReachabilityValidator
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ShallowReachabilityValidatorTest {

    private val logger = RecordingLogger()
    private val ctx = ProbeContext(stage = ValidationStage.SHALLOW, timeoutMs = 5_000, engineCaps = emptySet<EngineCapability>(), nowMs = 0)

    private fun target(url: String = "http://stream.invalid/a.m3u8") =
        StreamTarget(url = url, userAgent = null, referrer = null)

    @Test
    fun `a 200 to HEAD passes`() {
        val calls = mutableListOf<HttpRequest>()
        val fake = FakeHttpFetcher { request ->
            calls += request
            ilab.iptv.player.core.common.AppResult.Ok(HttpResponse(200, ByteArray(0), null, 4, false))
        }
        val result = runBlocking { ShallowReachabilityValidator(fake, logger).validate(target(), ctx) }
        assertThat(result.passed).isTrue()
        assertThat(calls).hasSize(1)
        assertThat(calls[0].method).isEqualTo(HttpMethod.HEAD)
    }

    @Test
    fun `HEAD 405 falls back to a bounded GET`() {
        val calls = mutableListOf<HttpRequest>()
        val fake = FakeHttpFetcher { request ->
            calls += request
            when (request.method) {
                HttpMethod.HEAD -> ilab.iptv.player.core.common.AppResult.Ok(HttpResponse(405, ByteArray(0), null, 3, false))
                HttpMethod.GET -> ilab.iptv.player.core.common.AppResult.Ok(HttpResponse(206, ByteArray(64), null, 9, false))
            }
        }
        val result = runBlocking { ShallowReachabilityValidator(fake, logger).validate(target(), ctx) }
        assertThat(result.passed).isTrue()
        assertThat(calls.map { it.method }).containsExactly(HttpMethod.HEAD, HttpMethod.GET)
        assertThat(calls[1].firstBytesOnly).isEqualTo(PipelineLimits().shallowFirstBytes)
    }

    @Test
    fun `an HTTP error on both legs fails`() {
        val result = runBlocking {
            ShallowReachabilityValidator(FakeHttpFetcher(FakeHttpFetcher.status(500)), logger).validate(target(), ctx)
        }
        assertThat(result.passed).isFalse()
        assertThat(result.detail).contains("500")
        assertThat(logger.count(ilab.iptv.player.core.common.EventCodes.VAL_SHALLOW_FAIL)).isEqualTo(1)
    }

    @Test
    fun `a transport failure is reported with its class`() {
        val result = runBlocking {
            ShallowReachabilityValidator(FakeHttpFetcher(FakeHttpFetcher.fail()), logger).validate(target(), ctx)
        }
        assertThat(result.passed).isFalse()
        assertThat(result.evidence["failure"]).isEqualTo("TIMEOUT")
    }

    @Test
    fun `a non-HTTP scheme is marked unsupported instead of probed`() {
        val calls = mutableListOf<HttpRequest>()
        val fake = FakeHttpFetcher { request ->
            calls += request
            ilab.iptv.player.core.common.AppResult.Ok(HttpResponse(200, ByteArray(0), null, 1, false))
        }
        val result = runBlocking { ShallowReachabilityValidator(fake, logger).validate(target("rtsp://example/x"), ctx) }
        assertThat(result.passed).isFalse()
        assertThat(result.evidence["phase"]).isEqualTo("unsupported")
        assertThat(calls).isEmpty()
    }
}
