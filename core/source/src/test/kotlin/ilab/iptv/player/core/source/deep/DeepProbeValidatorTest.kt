package ilab.iptv.player.core.source.deep

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.model.ProbeContext
import ilab.iptv.player.core.model.StreamTarget
import ilab.iptv.player.core.model.ValidationStage
import ilab.iptv.player.core.network.HttpResponse
import ilab.iptv.player.core.source.FakeHttpFetcher
import ilab.iptv.player.core.source.RecordingLogger
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DeepProbeValidatorTest {

    private val logger = RecordingLogger()
    private val limits = PipelineLimits(deepFirstBytes = 4096)

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
        1080p/index.m3u8
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXTINF:6.0,
        seg-1.ts
        #EXTINF:6.0,
        seg-2.ts
    """.trimIndent()

    private fun ctx() = ProbeContext(
        stage = ValidationStage.DEEP,
        timeoutMs = 12_000,
        engineCaps = emptySet(),
        nowMs = 1_000,
    )

    private fun target(url: String = "https://cdn.invalid/live/master.m3u8") =
        StreamTarget(url = url, userAgent = null, referrer = null)

    private fun validator(
        fetcher: FakeHttpFetcher,
        trackProbe: TrackProbe? = null,
    ) = DeepProbeValidator(fetcher = fetcher, logger = logger, limits = limits, trackProbe = trackProbe)

    private fun playlist(text: String): AppResult<HttpResponse> = AppResult.Ok(
        HttpResponse(200, text.encodeToByteArray(), "application/vnd.apple.mpegurl", 12, truncated = false),
    )

    private fun body(bytes: Int, status: Int = 200): AppResult<HttpResponse> = AppResult.Ok(
        HttpResponse(status, ByteArray(bytes), "video/mp2t", 12, truncated = false),
    )

    @Test
    fun `master to variant to edge segment is a pass with the declared codecs`() {
        val fetcher = FakeHttpFetcher { request ->
            when (request.url) {
                "https://cdn.invalid/live/master.m3u8" -> playlist(master)
                "https://cdn.invalid/live/1080p/index.m3u8" -> playlist(media)
                else -> body(1_880)
            }
        }
        val result = runBlocking { validator(fetcher).validate(target(), ctx()) }

        assertThat(result.passed).isTrue()
        assertThat(result.evidence["segStatus"]).isEqualTo(200)
        assertThat(result.evidence["segBytes"]).isEqualTo(1_880)
        assertThat(result.evidence["vcodec"]).isEqualTo("video/avc")
        assertThat(result.evidence["acodec"]).isEqualTo("audio/mp4a-latm")
        assertThat(result.evidence["w"]).isEqualTo(1920)
        assertThat(result.evidence["h"]).isEqualTo(1080)
        assertThat(result.evidence["codecSource"]).isEqualTo("declared")
        assertThat(result.evidence["phase"]).isEqualTo("edge")
        // The edge segment (last) is tried first, then the head (docs/02 §6.1 末片→首片).
        assertThat(fetcher.requests.map { it.url }).containsExactly(
            "https://cdn.invalid/live/master.m3u8",
            "https://cdn.invalid/live/1080p/index.m3u8",
            "https://cdn.invalid/live/1080p/seg-2.ts",
        ).inOrder()
        assertThat(logger.count(EventCodes.VAL_DEEP_OK)).isEqualTo(1)
    }

    @Test
    fun `a media playlist url is probed directly`() {
        val fetcher = FakeHttpFetcher { request ->
            if (request.url.endsWith("index.m3u8")) playlist(media) else body(64)
        }
        val result = runBlocking {
            validator(fetcher).validate(target("https://cdn.invalid/live/index.m3u8"), ctx())
        }
        assertThat(result.passed).isTrue()
        assertThat(result.evidence["codecSource"]).isEqualTo("unknown")
        assertThat(result.evidence["segments"]).isEqualTo(2)
    }

    @Test
    fun `a segment that does not answer fails the stream`() {
        val fetcher = FakeHttpFetcher { request ->
            when {
                request.url.endsWith("master.m3u8") -> playlist(master)
                request.url.endsWith("index.m3u8") -> playlist(media)
                else -> body(0, status = 503)
            }
        }
        val result = runBlocking { validator(fetcher).validate(target(), ctx()) }
        assertThat(result.passed).isFalse()
        assertThat(result.evidence["failure"]).isEqualTo("HTTP_SERVER")
        assertThat(logger.count(EventCodes.VAL_DEEP_FAIL)).isEqualTo(1)
    }

    @Test
    fun `the head segment is used when the edge one is gone`() {
        var segmentsSeen = 0
        val fetcher = FakeHttpFetcher { request ->
            when {
                request.url.endsWith("master.m3u8") -> playlist(master)
                request.url.endsWith("index.m3u8") -> playlist(media)
                request.url.endsWith("seg-2.ts") -> { segmentsSeen++; body(0, status = 404) }
                else -> { segmentsSeen++; body(512) }
            }
        }
        val result = runBlocking { validator(fetcher).validate(target(), ctx()) }
        assertThat(result.passed).isTrue()
        assertThat(result.evidence["phase"]).isEqualTo("first")
        assertThat(segmentsSeen).isEqualTo(2)
    }

    @Test
    fun `a body that is not a playlist is a direct byte pull`() {
        val fetcher = FakeHttpFetcher { body(1_024) }
        val result = runBlocking {
            validator(fetcher).validate(target("https://cdn.invalid/live/channel.ts"), ctx())
        }
        assertThat(result.passed).isTrue()
        assertThat(result.evidence["phase"]).isEqualTo("direct")
        assertThat(result.evidence["segStatus"]).isEqualTo(200)
        assertThat(fetcher.requests).hasSize(1)
    }

    @Test
    fun `a transport failure keeps its failure class`() {
        val fetcher = FakeHttpFetcher(
            FakeHttpFetcher.fail(AppError.timeout(EventCodes.NET_REQ_FAIL)),
        )
        val result = runBlocking { validator(fetcher).validate(target(), ctx()) }
        assertThat(result.passed).isFalse()
        assertThat(result.evidence["failure"]).isEqualTo("TIMEOUT")
        assertThat(result.evidence["phase"]).isEqualTo("playlist")
    }

    @Test
    fun `an empty playlist is EMPTY_MEDIA rather than a silent pass`() {
        val fetcher = FakeHttpFetcher { request ->
            playlist("#EXTM3U\n#EXT-X-VERSION:3\n")
        }
        val result = runBlocking { validator(fetcher).validate(target(), ctx()) }
        assertThat(result.passed).isFalse()
        assertThat(result.evidence["failure"]).isEqualTo("EMPTY_MEDIA")
    }

    @Test
    fun `a non-http scheme is reported as unsupported and never fetched`() {
        val fetcher = FakeHttpFetcher { body(1) }
        val result = runBlocking {
            validator(fetcher).validate(target("rtsp://cdn.invalid/live"), ctx())
        }
        assertThat(result.passed).isFalse()
        assertThat(result.evidence["phase"]).isEqualTo("unsupported")
        assertThat(fetcher.requests).isEmpty()
    }

    @Test
    fun `a bound track probe replaces the declared reading`() {
        val fetcher = FakeHttpFetcher { request ->
            when {
                request.url.endsWith("master.m3u8") -> playlist(master)
                request.url.endsWith("index.m3u8") -> playlist(media)
                else -> body(32)
            }
        }
        val probe = TrackProbe { _, _ ->
            TrackProbeResult(
                videoCodec = "video/hevc",
                audioCodec = "audio/ac3",
                width = 3840,
                height = 2160,
                detail = "2 tracks",
            )
        }
        val result = runBlocking { validator(fetcher, probe).validate(target(), ctx()) }
        assertThat(result.passed).isTrue()
        assertThat(result.evidence["vcodec"]).isEqualTo("video/hevc")
        assertThat(result.evidence["acodec"]).isEqualTo("audio/ac3")
        assertThat(result.evidence["w"]).isEqualTo(3840)
        assertThat(result.evidence["codecSource"]).isEqualTo("probe")
    }
}
