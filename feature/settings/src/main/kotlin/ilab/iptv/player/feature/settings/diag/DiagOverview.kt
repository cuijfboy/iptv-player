package ilab.iptv.player.feature.settings.diag

import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogEvent

/** One `标签：值` line of the 运行概览 (docs/03 §7.1). */
data class DiagFact(val label: String, val value: String)

/** One titled group of [facts] — the panel renders these, and `stats.json` mirrors them. */
data class DiagBlock(val title: String, val facts: List<DiagFact>)

/** One subscription row (docs/03 §7.1 源健康: 各源条目数与成功率). */
data class DiagSourceLine(
    val label: String,
    val enabled: Boolean,
    val entries: Int?,
    /** `ok` / `failed` / `never`. */
    val result: String,
    val atMs: Long?,
)

/**
 * What the log ring knows about playback (docs/03 §7.1 播放: 平均起播耗时、故障转移次数、失败原因).
 *
 * Deliberately derived from the ring and nothing else: this round must not open a second data channel
 * (dispatch item 2), and the ring already carries the four codes of the playback path (§3.3.1).
 */
data class DiagPlaybackSummary(
    val starts: Int,
    val firstFrames: Int,
    val failovers: Int,
    val lastCostMs: Long?,
    val lastFailCode: String?,
    val lastFailCategory: String?,
)

/**
 * Every input the overview needs, already gathered from the existing repositories, the injected
 * `FileSink`/`LogBus` and the platform. Keeping this a plain data class is what makes
 * [DiagOverview.build] unit-testable — the panel itself only formats.
 */
data class DiagOverviewInput(
    val appVersion: String,
    val abi: String,
    val sdk: String,
    val deviceModel: String,
    val usedMemoryMb: Long,
    val maxMemoryMb: Long,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val network: String,
    val channelCount: Int,
    val groupCount: Int,
    val streamCount: Int,
    val hiddenCount: Int,
    val favoriteCount: Int,
    val sources: List<DiagSourceLine>,
    val playback: DiagPlaybackSummary,
    val logLevel: String,
    val fileLogEnabled: Boolean,
    val ringSize: Int,
    val ringCapacity: Int,
    val fileLogSummary: String,
    val lastRefresh: String,
)

/**
 * Builds the 运行概览 blocks of docs/03 §7.1 from [DiagOverviewInput] — and nothing else. In
 * particular it does not reach for a repository, a clock or a device: the sources of those numbers
 * are the existing repository ports and the log ring (dispatch item 2, "不要另造数据通道").
 */
object DiagOverview {

    fun build(input: DiagOverviewInput): List<DiagBlock> = listOf(
        DiagBlock(
            title = "设备",
            facts = listOf(
                DiagFact("机型", input.deviceModel),
                DiagFact("ABI", input.abi),
                DiagFact("Android SDK", input.sdk),
                DiagFact("内存", "已用 ${input.usedMemoryMb} / 上限 ${input.maxMemoryMb} MB"),
                DiagFact("存储", storageText(input.storageFreeBytes, input.storageTotalBytes)),
                DiagFact("网络", input.network),
            ),
        ),
        DiagBlock(
            title = "应用",
            facts = listOf(
                DiagFact("版本", input.appVersion),
                DiagFact("日志级别", input.logLevel),
                DiagFact("文件日志", if (input.fileLogEnabled) "开" else "关"),
                DiagFact("内存环", "${input.ringSize} / ${input.ringCapacity}"),
                DiagFact("日志文件", input.fileLogSummary),
            ),
        ),
        DiagBlock(
            title = "数据",
            facts = listOf(
                DiagFact("频道", input.channelCount.toString()),
                DiagFact("分组", input.groupCount.toString()),
                DiagFact("流", input.streamCount.toString()),
                DiagFact("隐藏 / 收藏", "${input.hiddenCount} / ${input.favoriteCount}"),
            ),
        ),
        DiagBlock(
            title = "源健康",
            facts = buildList {
                val ok = input.sources.count { it.result == RESULT_OK }
                val failed = input.sources.count { it.result == RESULT_FAILED }
                val never = input.sources.count { it.result == RESULT_NEVER }
                add(DiagFact("订阅", "共 ${input.sources.size} / 启用 ${input.sources.count { it.enabled }}"))
                add(DiagFact("结果", "成功 $ok / 失败 $failed / 未取回 $never"))
                add(DiagFact("最近刷新", input.lastRefresh))
                input.sources.take(MAX_SOURCE_LINES).forEach { line ->
                    add(DiagFact(line.label, sourceText(line)))
                }
            },
        ),
        DiagBlock(
            title = "播放",
            facts = listOf(
                DiagFact("起播", "${input.playback.starts} 次 / 出画 ${input.playback.firstFrames} 次"),
                DiagFact("最近起播耗时", input.playback.lastCostMs?.let { "$it ms" } ?: "无"),
                DiagFact("故障转移", "${input.playback.failovers} 次"),
                DiagFact("最近失败", failureText(input.playback)),
            ),
        ),
    )

    /**
     * Reads the playback facts out of the ring (docs/03 §3.3.1 播放/故障转移路径): the four codes the
     * playback path is required to emit are enough to answer "did it play, how long did it take, did it
     * fall over, and why did it last fail" without a new metric channel.
     */
    fun playbackOf(events: List<LogEvent>): DiagPlaybackSummary {
        var starts = 0
        var firstFrames = 0
        var failovers = 0
        var lastCostMs: Long? = null
        var lastFailCode: String? = null
        var lastFailCategory: String? = null
        events.forEach { event ->
            when (event.code) {
                EventCodes.PLAY_PREPARE_START -> starts++
                EventCodes.PLAY_FIRST_FRAME -> {
                    firstFrames++
                    lastCostMs = longField(event, "costMs") ?: lastCostMs
                }

                EventCodes.PLAY_FAILOVER -> failovers++
                EventCodes.PLAY_PREPARE_FAIL -> {
                    lastFailCode = event.code
                    lastFailCategory = event.fields["errCategory"]?.toString()
                }
            }
        }
        return DiagPlaybackSummary(starts, firstFrames, failovers, lastCostMs, lastFailCode, lastFailCategory)
    }

    /** `1.2 GB / 5.0 GB` — a TV shows sizes, not byte counts. */
    fun storageText(freeBytes: Long, totalBytes: Long): String =
        "${bytes(freeBytes)} 可用 / ${bytes(totalBytes)} 共"

    fun bytes(value: Long): String = when {
        value >= GIB -> "%.1f GB".format(value / GIB.toDouble())
        value >= MIB -> "%.1f MB".format(value / MIB.toDouble())
        value >= KIB -> "%.1f KB".format(value / KIB.toDouble())
        else -> "$value B"
    }

    private fun failureText(playback: DiagPlaybackSummary): String =
        playback.lastFailCode?.let { code ->
            playback.lastFailCategory?.let { "$code（$it）" } ?: code
        } ?: "无"

    private fun sourceText(line: DiagSourceLine): String = buildString {
        append(if (line.enabled) "已启用" else "已停用")
        append(" / ")
        append(
            when (line.result) {
                RESULT_OK -> line.entries?.let { "上次成功 $it 条" } ?: "上次成功"
                RESULT_FAILED -> "上次失败"
                else -> "尚未取回"
            },
        )
    }

    private fun longField(event: LogEvent, key: String): Long? =
        when (val value = event.fields[key]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }

    /** Keep the panel readable: the full per-source list belongs to `stats.json`. */
    const val MAX_SOURCE_LINES: Int = 6

    const val RESULT_OK: String = "ok"
    const val RESULT_FAILED: String = "failed"
    const val RESULT_NEVER: String = "never"

    private const val KIB = 1024L
    private const val MIB = 1024L * KIB
    private const val GIB = 1024L * MIB
}
