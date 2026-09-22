package ilab.iptv.player.feature.settings.diag

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.EpgCoverage
import org.junit.Test

/**
 * The 运行概览 of docs/03 §7.1: the panel's blocks are built from a snapshot, and the playback block is
 * derived from the log ring's own events — no second data channel (dispatch item 2).
 */
class DiagOverviewTest {

    private fun playbackEvent(seq: Long, code: String, fields: Map<String, Any?> = emptyMap()) = LogEvent(
        seq = seq,
        ts = 1_700_000_000_000L + seq,
        elapsedMs = seq,
        level = LogLevel.INFO,
        category = LogCategory.PLAYER,
        code = code,
        message = code,
        fields = fields,
        error = null,
        thread = "test",
        screen = "player",
        sessionId = "play-1",
    )

    private fun input(
        sources: List<DiagSourceLine> = emptyList(),
        playback: DiagPlaybackSummary = DiagPlaybackSummary(0, 0, 0, null, null, null),
        epg: DiagEpgSummary? = null,
    ) = DiagOverviewInput(
        appVersion = "0.5.2 (5)",
        abi = "arm64-v8a",
        sdk = "31 (Android 12)",
        deviceModel = "Sony BRAVIA",
        usedMemoryMb = 96,
        maxMemoryMb = 256,
        storageFreeBytes = 1_200_000_000L,
        storageTotalBytes = 5_000_000_000L,
        network = "WiFi / 已验证",
        channelCount = 658,
        groupCount = 5,
        streamCount = 1_316,
        hiddenCount = 3,
        favoriteCount = 4,
        sources = sources,
        playback = playback,
        logLevel = "INFO",
        fileLogEnabled = true,
        ringSize = 42,
        ringCapacity = 2000,
        fileLogSummary = "3 个 / 1.2 MB",
        lastRefresh = "2026-09-22 06:00（12 分钟前）",
        epg = epg,
    )

    @Test
    fun `the EPG block appears last when there are EPG facts, and reports both coverage readings`() {
        val blocks = DiagOverview.build(
            input(
                epg = DiagEpgSummary(
                    lastFetch = "2026-09-22 08:12（34 分钟前）",
                    sources = "共 4 / 启用 4 · 上次 OK:177447",
                    mainstream = "153 / 156 = 98.1%",
                    coverage = "209 / 658 = 31.8%",
                    minInterval = "6 小时",
                    enabled = true,
                ),
            ),
        )

        assertThat(blocks.map { it.title }).containsExactly("设备", "应用", "数据", "源健康", "播放", "EPG").inOrder()
        assertThat(value(blocks, "EPG", "自动更新")).isEqualTo("开（间隔 6 小时）")
        assertThat(value(blocks, "EPG", "上次拉取")).isEqualTo("2026-09-22 08:12（34 分钟前）")
        assertThat(value(blocks, "EPG", "主流覆盖")).isEqualTo("153 / 156 = 98.1%")
    }

    @Test
    fun `the no-EPG case keeps the page exactly as it was`() {
        assertThat(DiagOverview.build(input()).map { it.title })
            .containsExactly("设备", "应用", "数据", "源健康", "播放").inOrder()
    }

    @Test
    fun `the mainstream slice is 央视 + 卫视 + 港澳台, not every non-local group`() {
        val coverage = EpgCoverage(
            matched = 209,
            total = 658,
            byGroup = mapOf(
                ChannelGroup.CCTV to 80,
                ChannelGroup.SATELLITE to 69,
                ChannelGroup.HK_MO_TW to 4,
                ChannelGroup.LOCAL to 55,
                ChannelGroup.OTHER to 1,
            ),
            byGroupTotal = mapOf(
                ChannelGroup.CCTV to 80,
                ChannelGroup.SATELLITE to 69,
                ChannelGroup.HK_MO_TW to 7,
                ChannelGroup.LOCAL to 500,
                ChannelGroup.OTHER to 2,
            ),
        )

        assertThat(DiagOverview.mainstreamOf(coverage)).isEqualTo(153 to 156)
        assertThat(DiagOverview.MAINSTREAM_GROUPS)
            .containsExactly(ChannelGroup.CCTV, ChannelGroup.SATELLITE, ChannelGroup.HK_MO_TW)
    }

    @Test
    fun `the overview covers every block docs 03 section 7_1 asks for`() {
        val blocks = DiagOverview.build(input())

        assertThat(blocks.map { it.title }).containsExactly("设备", "应用", "数据", "源健康", "播放").inOrder()
        assertThat(value(blocks, "设备", "ABI")).isEqualTo("arm64-v8a")
        assertThat(value(blocks, "设备", "存储")).contains("可用")
        assertThat(value(blocks, "数据", "频道")).isEqualTo("658")
        assertThat(value(blocks, "数据", "流")).isEqualTo("1316")
        assertThat(value(blocks, "应用", "文件日志")).isEqualTo("开")
        assertThat(value(blocks, "应用", "内存环")).isEqualTo("42 / 2000")
    }

    @Test
    fun `the source block counts the funnel and lists the rows`() {
        val sources = listOf(
            DiagSourceLine("源 A", enabled = true, entries = 412, result = DiagOverview.RESULT_OK, atMs = 1L),
            DiagSourceLine("源 B", enabled = false, entries = 0, result = DiagOverview.RESULT_FAILED, atMs = 2L),
            DiagSourceLine("源 C", enabled = true, entries = null, result = DiagOverview.RESULT_NEVER, atMs = null),
        )

        val blocks = DiagOverview.build(input(sources = sources))

        assertThat(value(blocks, "源健康", "订阅")).isEqualTo("共 3 / 启用 2")
        assertThat(value(blocks, "源健康", "结果")).isEqualTo("成功 1 / 失败 1 / 未取回 1")
        assertThat(value(blocks, "源健康", "源 A")).isEqualTo("已启用 / 上次成功 412 条")
        assertThat(value(blocks, "源健康", "源 B")).isEqualTo("已停用 / 上次失败")
        assertThat(value(blocks, "源健康", "源 C")).isEqualTo("已启用 / 尚未取回")
    }

    @Test
    fun `the playback block is read out of the log ring`() {
        val events = listOf(
            playbackEvent(1, "PLAY_PREPARE_START", mapOf("channelId" to 7L)),
            playbackEvent(2, "PLAY_FIRST_FRAME", mapOf("costMs" to 2450L, "vcodec" to "video/avc")),
            playbackEvent(3, "PLAY_PREPARE_FAIL", mapOf("errCategory" to "HTTP_403", "httpStatus" to 403)),
            playbackEvent(4, "PLAY_FAILOVER", mapOf("reason" to "prepare_fail")),
            playbackEvent(5, "PLAY_PREPARE_START", mapOf("channelId" to 8L)),
        )

        val summary = DiagOverview.playbackOf(events)

        assertThat(summary.starts).isEqualTo(2)
        assertThat(summary.firstFrames).isEqualTo(1)
        assertThat(summary.failovers).isEqualTo(1)
        assertThat(summary.lastCostMs).isEqualTo(2450L)
        assertThat(summary.lastFailCode).isEqualTo("PLAY_PREPARE_FAIL")
        assertThat(summary.lastFailCategory).isEqualTo("HTTP_403")

        val blocks = DiagOverview.build(input(playback = summary))
        assertThat(value(blocks, "播放", "起播")).isEqualTo("2 次 / 出画 1 次")
        assertThat(value(blocks, "播放", "最近起播耗时")).isEqualTo("2450 ms")
        assertThat(value(blocks, "播放", "最近失败")).isEqualTo("PLAY_PREPARE_FAIL（HTTP_403）")
    }

    @Test
    fun `an empty ring reports no playback instead of zero-ish noise`() {
        val summary = DiagOverview.playbackOf(emptyList())

        assertThat(summary).isEqualTo(DiagPlaybackSummary(0, 0, 0, null, null, null))
        assertThat(value(DiagOverview.build(input()), "播放", "最近失败")).isEqualTo("无")
        assertThat(value(DiagOverview.build(input()), "播放", "最近起播耗时")).isEqualTo("无")
    }

    @Test
    fun `sizes are rendered for a television, not in bytes`() {
        assertThat(DiagOverview.bytes(512)).isEqualTo("512 B")
        assertThat(DiagOverview.bytes(2048)).isEqualTo("2.0 KB")
        assertThat(DiagOverview.bytes(5 * 1024L * 1024L)).isEqualTo("5.0 MB")
        assertThat(DiagOverview.storageText(1024L * 1024L * 1024L, 5L * 1024L * 1024L * 1024L))
            .isEqualTo("1.0 GB 可用 / 5.0 GB 共")
    }

    @Test
    fun `meta and stats encode as json and redact what goes through them`() {
        val json = DiagJson.encode(
            linkedMapOf(
                "version" to "0.5.2 (5)",
                "ok" to true,
                "channels" to 658,
                "nothing" to null,
                "note" to "he said \"hi\"\n",
                "source" to "https://portal.example.com/live/user/secretkey/1.m3u8?token=SECRET9",
                "list" to listOf(1, 2),
            ),
            ilab.iptv.player.core.log.UrlRedactor(),
        )

        assertThat(json).contains(""""version":"0.5.2 (5)"""")
        assertThat(json).contains(""""ok":true""")
        assertThat(json).contains(""""channels":658""")
        assertThat(json).contains(""""nothing":null""")
        assertThat(json).contains("""he said \"hi\"\n""")
        assertThat(json).contains(""""list":[1,2]""")
        // The export pipeline's redaction (§11/W7) applies to JSON values too.
        assertThat(json).contains("token=***")
        assertThat(json).doesNotContain("SECRET9")
        assertThat(json).doesNotContain("secretkey")
    }

    private fun value(blocks: List<DiagBlock>, title: String, label: String): String =
        blocks.first { it.title == title }.facts.first { it.label == label }.value
}
