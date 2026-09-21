package ilab.iptv.player.core.epg

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The E4 provider over a fake transport: the request it builds, gzip handling, and the two fetch event
 * codes. No socket, so this runs in CI and honours the job's "HTTP 用假体，不真连网".
 */
class XmltvHttpEpgProviderTest {

    private val logger = RecordingLogger()
    private val clock = FakeClock()
    private val xml = "<?xml version=\"1.0\"?><tv><channel id=\"c1\"/></tv>"

    private fun provider(fetcher: FakeStreamingFetcher, timeoutMs: Long = 5_000) = XmltvHttpEpgProvider(
        id = "test.epg",
        label = "Test EPG",
        url = "https://example.invalid/epg.xml",
        fetcher = fetcher,
        logger = logger,
        timeoutMs = timeoutMs,
    )

    @Test
    fun `a plain body is streamed through and EPG_FETCH_OK is logged with the declared size`() {
        val fetcher = FakeStreamingFetcher { FakeStreamingFetcher.body(xml) }
        val result = runBlocking { provider(fetcher).fetch(clock) }

        assertThat(result).isInstanceOf(AppResult.Ok::class.java)
        val stream = (result as AppResult.Ok).value
        assertThat(stream.sourceId).isEqualTo("test.epg")
        assertThat(stream.bytes).isEqualTo(xml.toByteArray().size.toLong())
        assertThat(stream.body.readAllText()).isEqualTo(xml)
        assertThat(logger.count(EventCodes.EPG_FETCH_OK)).isEqualTo(1)
        assertThat(logger.count(EventCodes.EPG_FETCH_FAIL)).isEqualTo(0)
    }

    @Test
    fun `a gzipped body is inflated before the parser ever sees it`() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(xml.toByteArray()) }
        val payload = out.toByteArray()
        val fetcher = FakeStreamingFetcher { FakeStreamingFetcher.bytes(payload) }

        val result = runBlocking { provider(fetcher).fetch(clock) }

        val stream = (result as AppResult.Ok).value
        // `bytes` is what the server declared (the compressed size); the body is decoded text.
        assertThat(stream.bytes).isEqualTo(payload.size.toLong())
        assertThat(stream.body.readAllText()).isEqualTo(xml)
        assertThat(logger.events.last { it.code == EventCodes.EPG_FETCH_OK }.fields["gzip"]).isEqualTo(true)
    }

    @Test
    fun `the request carries the configured timeout and no byte cap`() {
        val fetcher = FakeStreamingFetcher { FakeStreamingFetcher.body(xml) }
        runBlocking { provider(fetcher, timeoutMs = 12_345).fetch(clock) }

        assertThat(fetcher.requests.single().timeoutMs).isEqualTo(12_345L)
        assertThat(fetcher.requests.single().firstBytesOnly).isNull()
    }

    @Test
    fun `an HTTP failure is reported, not thrown, and logged as EPG_FETCH_FAIL`() {
        val fetcher = FakeStreamingFetcher { FakeStreamingFetcher.httpFailure(503) }
        val result = runBlocking { provider(fetcher).fetch(clock) }

        val error = (result as AppResult.Err).error
        assertThat(error.httpStatus).isEqualTo(503)
        assertThat(error.failure).isEqualTo(FailureClass.HTTP_SERVER)
        assertThat(logger.count(EventCodes.EPG_FETCH_FAIL)).isEqualTo(1)
        assertThat(logger.count(EventCodes.EPG_FETCH_OK)).isEqualTo(0)
    }

    @Test
    fun `a transport failure keeps its failure class and does not retry inside the provider`() {
        val fetcher = FakeStreamingFetcher {
            FakeStreamingFetcher.failure(AppError.timeout(EventCodes.NET_REQ_FAIL))
        }
        val result = runBlocking { provider(fetcher).fetch(clock) }

        assertThat((result as AppResult.Err).error.failure).isEqualTo(FailureClass.TIMEOUT)
        // Retry is the transport's job (docs/02 §6.1); a second loop here would double the attempts.
        assertThat(fetcher.attempts).isEqualTo(1)
    }
}
