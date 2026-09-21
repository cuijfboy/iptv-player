package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.source.normalize.Keys
import ilab.iptv.player.core.source.normalize.PlaylistNormalizer
import ilab.iptv.player.core.source.parser.M3uParser
import ilab.iptv.player.core.source.parser.PlaylistFormat
import ilab.iptv.player.core.source.parser.PlaylistParsers
import ilab.iptv.player.core.source.parser.TxtParser
import java.nio.file.Files
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The fixture-driven regression suite for docs/04 P1-1: every sample in `tools/fixtures/` is parsed
 * and checked against the numbers written down in `docs/05-过程记录/05-P0构建验证.md` §11. If a rule
 * changes, this test and that section change together.
 */
class FixtureRegressionTest {

    @Test
    fun `the curated valid sample yields exactly the documented entries`() {
        val outcome = M3uParser.parse(Fixtures.text("m3u/valid.m3u"), sourceId = "valid")

        assertThat(outcome.format).isEqualTo(PlaylistFormat.M3U)
        assertThat(outcome.entries).hasSize(4)
        assertThat(outcome.skipped).isEqualTo(0)

        val cctv1 = outcome.entries[0]
        assertThat(cctv1.name).isEqualTo("CCTV-1")
        assertThat(cctv1.tvgId).isEqualTo("CCTV1")
        assertThat(cctv1.tvgName).isEqualTo("CCTV-1")
        assertThat(cctv1.channelNo).isEqualTo(1)
        assertThat(cctv1.logo).isEqualTo("http://logo.example/cctv1.png")
        assertThat(cctv1.groupTitle).isEqualTo("央视")

        val hunan = outcome.entries[1]
        assertThat(hunan.name).isEqualTo("湖南卫视")
        assertThat(hunan.channelNo).isEqualTo(31)
        assertThat(hunan.groupTitle).isEqualTo("卫视")

        val phoenix = outcome.entries[2]
        assertThat(phoenix.name).isEqualTo("凤凰中文")
        assertThat(phoenix.groupTitle).isEqualTo("港澳台") // from #EXTGRP

        val cctv2 = outcome.entries[3]
        assertThat(cctv2.tvgId).isEqualTo("CCTV2")
        assertThat(cctv2.userAgent).isEqualTo("FixtureUA/1.0")
        assertThat(cctv2.referrer).isEqualTo("http://ref.example/")
    }

    @Test
    fun `the attribute edge sample pins the parsing rules`() {
        val outcome = M3uParser.parse(Fixtures.text("m3u/attrs-edge.m3u"), sourceId = "attrs")

        assertThat(outcome.entries).hasSize(5)
        assertThat(outcome.skipped).isEqualTo(0)

        assertThat(outcome.entries[0].groupTitle).isEqualTo("新闻,财经")
        assertThat(outcome.entries[0].channelNo).isEqualTo(12)
        assertThat(outcome.entries[1].tvgId).isEqualTo("CCTV9")
        assertThat(outcome.entries[1].tvgName).isEqualTo("记录")
        assertThat(outcome.entries[2].name).isEqualTo("No Attrs At All")
        assertThat(outcome.entries[2].tvgId).isNull()
        assertThat(outcome.entries[3].channelNo).isNull() // tvg-chno=abc
        assertThat(outcome.entries[3].logo).isNull() // tvg-logo=""
        assertThat(outcome.entries[4].name).isEqualTo("Only TVG Name") // empty title → tvg-name
    }

    @Test
    fun `the malformed sample drops rows without throwing`() {
        val outcome = M3uParser.parse(Fixtures.text("m3u/malformed.m3u"), sourceId = "malformed")

        assertThat(outcome.entries.map { it.name }).containsExactly("Gamma", "Epsilon").inOrder()
        // Alpha (EXTINF without URL), Beta (replaced), an orphan URL, "not-a-url", a blank name,
        // and the trailing #EXTINF.
        assertThat(outcome.skipped).isEqualTo(6)
    }

    @Test
    fun `bom and crlf survive decoding intact`() {
        val outcome = M3uParser.parse(Fixtures.text("m3u/bom-crlf.m3u"), sourceId = "bom")

        assertThat(outcome.entries.map { it.name }).containsExactly("BOM Channel 1", "BOM Channel 2").inOrder()
        assertThat(outcome.entries[0].groupTitle).isEqualTo("央视")
    }

    @Test
    fun `header-only and empty files are empty playlists, not errors`() {
        val headerOnly = M3uParser.parse(Fixtures.text("m3u/header-only.m3u"), sourceId = "header")
        assertThat(headerOnly.entries).isEmpty()
        assertThat(headerOnly.skipped).isEqualTo(0)

        val empty = TxtParser.parse(Fixtures.text("m3u/empty.m3u"), sourceId = "empty")
        assertThat(empty.entries).isEmpty()
        assertThat(empty.skipped).isEqualTo(0)
    }

    @Test
    fun `the real baseline excerpt parses and folds its duplicates`() {
        val entries = M3uParser.parse(Fixtures.text("m3u/real-excerpt.m3u"), sourceId = "baseline").entries

        assertThat(entries).hasSize(10)
        assertThat(entries.any { it.name == "CCTV2" && it.url.contains("key=txiptv") && it.tvgId == "CCTV2" })
            .isTrue()

        val playlist = PlaylistNormalizer.normalize(entries)
        assertThat(playlist.streamDedupe.raw).isEqualTo(10)
        // Every URL differs, so dedupe removes nothing here; the duplicates are channel-level.
        assertThat(playlist.streamDedupe.removed).isEqualTo(0)
        assertThat(playlist.channels).hasSize(8)

        val cctv3 = playlist.channels.single { it.nameKey == "cctv3" }
        assertThat(cctv3.streams).hasSize(2)
        val cctv2 = playlist.channels.single { it.nameKey == "cctv2" }
        assertThat(cctv2.streams).hasSize(2)
        assertThat(playlist.channels.map { it.groupKey }.toSet()).containsExactly("央视")
    }

    @Test
    fun `non-utf8 samples go through the charset fallback`() {
        // The bytes on disk are GB18030, so this only passes if the fallback ran (a strict UTF-8
        // decode of them fails).
        val m3u = PlaylistParsers.parse(Fixtures.bytes("m3u/gb18030.m3u"), sourceId = "gb-m3u")
        val m3uOutcome = (m3u as AppResult.Ok).value
        assertThat(m3uOutcome.charset).isEqualTo("GB18030")
        assertThat(m3uOutcome.charsetEventCode).isEqualTo(EventCodes.NET_CHARSET_FALLBACK)
        assertThat(m3uOutcome.entries.map { it.name })
            .containsExactly("央视一套", "湖南卫视", "凤凰中文")
            .inOrder()

        val txt = PlaylistParsers.parse(Fixtures.bytes("txt/gb18030.txt"), sourceId = "gb-txt")
        val txtOutcome = (txt as AppResult.Ok).value
        assertThat(txtOutcome.format).isEqualTo(PlaylistFormat.TXT)
        assertThat(txtOutcome.charset).isEqualTo("GB18030")
        assertThat(txtOutcome.entries).hasSize(2)
        assertThat(txtOutcome.entries.map { it.groupTitle })
            .containsExactly("央视频道", "卫视")
            .inOrder()
    }

    @Test
    fun `a multi-megabyte url and attribute value parse in one pass`() {
        val longUrl = "http://long.example/" + "a".repeat(5_000_000) + "/index.m3u8"
        val longAttribute = "b".repeat(2_000_000)
        val text = "#EXTM3U\n" +
            "#EXTINF:-1 tvg-name=\"$longAttribute\" group-title=\"央视\",Long Line\n" +
            "$longUrl\n"

        val outcome = M3uParser.parse(text, sourceId = "long")

        assertThat(outcome.entries).hasSize(1)
        assertThat(outcome.entries[0].url).isEqualTo(longUrl)
        assertThat(outcome.entries[0].tvgName).hasLength(2_000_000)
    }

    @Test
    fun `the fingerprint keys of the baseline excerpt are stable`() {
        val entries = M3uParser.parse(Fixtures.text("m3u/real-excerpt.m3u"), sourceId = "baseline").entries
        val normalized = PlaylistNormalizer.normalizeEntries(entries)

        val cctv13 = normalized.single { it.nameKey == "cctv13" }
        assertThat(cctv13.groupKey).isEqualTo("央视")
        assertThat(cctv13.urlHash).isEqualTo(Keys.urlHash("http://ali-m-l.cztv.com/channels/lantian/channel21/1080p.m3u8"))
        assertThat(cctv13.urlHash).hasLength(64)
    }

    /**
     * Runs against the whole 658-channel baseline when it has been staged into the git-ignored
     * `tools/fixtures/large/` (see the fixture README). CI has no such file, so the test skips
     * there rather than pretending it ran: the committed evidence is the excerpt plus the numbers
     * this prints when it does run.
     */
    @Test
    fun `the full validated baseline parses when it is staged locally`() {
        val full = Fixtures.path("large/validated.m3u")
        assumeTrue("tools/fixtures/large/validated.m3u is not staged; skipping", Files.isRegularFile(full))

        val outcome = (PlaylistParsers.parse(Files.readAllBytes(full), sourceId = "baseline-full") as AppResult.Ok).value
        val playlist = PlaylistNormalizer.normalize(outcome.entries)
        println(
            "FULL-BASELINE entries=${outcome.entries.size} skipped=${outcome.skipped} " +
                "lines=${outcome.lines} channels=${playlist.channels.size} streams=${playlist.entries.size} " +
                "streamDedupeRemoved=${playlist.streamDedupe.removed}",
        )

        assertThat(outcome.entries.size).isAtLeast(FULL_BASELINE_MIN_ENTRIES)
        assertThat(playlist.channels).isNotEmpty()
        assertThat(playlist.channels.size).isAtMost(outcome.entries.size)
    }

    private companion object {
        /** The 2026-09 baseline list has 658 channels; demand the parser at least see most of them. */
        const val FULL_BASELINE_MIN_ENTRIES = 600
    }
}
