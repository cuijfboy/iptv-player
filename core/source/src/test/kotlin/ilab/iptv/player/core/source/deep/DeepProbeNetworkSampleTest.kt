package ilab.iptv.player.core.source.deep

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.model.ProbeContext
import ilab.iptv.player.core.model.StreamTarget
import ilab.iptv.player.core.model.ValidationStage
import ilab.iptv.player.core.network.DEFAULT_USER_AGENT
import ilab.iptv.player.core.network.OkHttpFetcher
import ilab.iptv.player.core.source.RecordingLogger
import ilab.iptv.player.core.source.pipeline.PipelineLimits
import ilab.iptv.player.core.source.provider.BuiltInSources
import ilab.iptv.player.core.source.provider.RemotePlaylistSourceProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real-network self-proof: fetch the P2-4a aggregates and run the **real**
 * [DeepProbeValidator] (over [OkHttpFetcher]) against the first few stream URLs each one publishes.
 *
 * It is **opt-in** — `./gradlew :core:source:testDebugUnitTest -Piptv.netSample=1` — because it needs
 * the network and its verdicts depend on which public streams happen to be alive. CI and `check`
 * never run it (the same rule P2-4a's sampling followed), and the numbers it produces are what the
 * P2-4b report's 真网抽样 table cites.
 */
class DeepProbeNetworkSampleTest {

    private val logger = RecordingLogger()
    private val limits = PipelineLimits(deepFirstBytes = 512L * 1024)
    private val clock = object : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }

    @Test
    fun `probe a sample of real sources and write the table`() {
        assumeTrue(
            "real-network sample is opt-in: rerun with -Piptv.netSample=1",
            System.getProperty("iptv.netSample").isNullOrBlank().not(),
        )

        val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
        val fetcher = OkHttpFetcher(client, logger)
        val validator = DeepProbeValidator(fetcher = fetcher, logger = logger, limits = limits)
        val sampled = listOf(
            BuiltInSources.FANMINGMING_ITV,
            BuiltInSources.YANG_GATHER,
            BuiltInSources.GUOVIN_RESULT,
            BuiltInSources.SUPPRISE_LIVE,
            BuiltInSources.FANMINGMING_INDEX,
        )

        val lines = mutableListOf(
            "# P2-4b real-network deep-probe sample (${java.util.Date()})",
            "# UA: $DEFAULT_USER_AGENT",
            "source\tentries\turl\tpassed\tfailure\tstatus\tsegStatus\tsegBytes\tvcodec\tacodec\tw\th\tcodecSource\tphase\tms",
        )
        var probed = 0
        runBlocking {
            // Positive control: a public HLS ladder that is expected to be fetchable, so a green row
            // proves the probe's master → variant → segment walk and the declared-codec reading work
            // on real bytes (the aggregate catalogues below are the production inputs).
            coroutineScopeProbe(
                validator,
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                lines,
                "control.bipbop",
                0,
            )
            probed++
            for (id in sampled) {
                val descriptor = BuiltInSources.byId(id) ?: continue
                val provider = RemotePlaylistSourceProvider(descriptor, fetcher, logger, limits)
                val fetched = provider.fetch(clock)
                val entries = when (fetched) {
                    is AppResult.Ok -> fetched.value
                    is AppResult.Err -> {
                        lines += "$id\t0\t(list fetch failed)\tfalse\t${fetched.error.failure.name}\t${fetched.error.httpStatus}\t-1\t0\t-\t-\t0\t0\t-\tplaylist\t0"
                        continue
                    }
                }
                for (entry in entries.take(3)) {
                    coroutineScopeProbe(validator, entry.url, lines, id, entries.size)
                    probed++
                }
            }
        }

        val out = File("build/net-sample.txt")
        out.parentFile?.mkdirs()
        out.writeText(lines.joinToString("\n"))
        println("net sample written to ${out.absolutePath} ($probed probes)")
        assertThat(probed).isAtLeast(1)
    }

    private suspend fun coroutineScopeProbe(
        validator: DeepProbeValidator,
        url: String,
        lines: MutableList<String>,
        sourceId: String,
        entryCount: Int,
    ) {
        val target = StreamTarget(url = url, userAgent = null, referrer = null)
        val started = clock.nowMs()
        val result = validator.validate(
            target,
            ProbeContext(stage = ValidationStage.DEEP, timeoutMs = 12_000, engineCaps = emptySet(), nowMs = started),
        )
        val e = result.evidence
        lines += listOf(
            sourceId,
            entryCount.toString(),
            url.take(60),
            result.passed.toString(),
            e["failure"].toString(),
            e["status"].toString(),
            e["segStatus"].toString(),
            e["segBytes"].toString(),
            e["vcodec"].toString(),
            e["acodec"].toString(),
            e["w"].toString(),
            e["h"].toString(),
            e["codecSource"].toString(),
            e["phase"].toString(),
            (clock.nowMs() - started).toString(),
        ).joinToString("\t")
    }
}
