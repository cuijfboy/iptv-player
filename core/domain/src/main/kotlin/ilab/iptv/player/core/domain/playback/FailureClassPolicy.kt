package ilab.iptv.player.core.domain.playback

import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.FailureClass

/** One step of a §4.6 recipe, in the vocabulary of the frozen [FailoverAction] set. */
enum class FailoverStep {

    /** `RetrySame`: prepare the same stream again (delay comes from the plan's row). */
    RETRY_SAME,

    /** `Backoff`: wait, then ask the policy again (still the same stream). */
    BACKOFF,

    /** `SwitchTo(next)`: move the channel to another candidate. */
    SWITCH_NEXT,

    /** `ResolveFresh`: re-probe this one channel on demand. */
    RESOLVE_FRESH,

    /** `GiveUp`: end the session with an explicit user message. */
    GIVE_UP,
}

/** How a failure class affects the failing stream's standing in later selections. */
enum class StreamDemotion {

    /** No standing change; the stream stays selectable as-is. */
    NONE,

    /** 冷却降权: keep the stream, but rank it behind healthy ones while its failure streak is fresh. */
    COOLDOWN,

    /** 永久降权: the source answered "gone" (4xx/410), so never pick it again in this session. */
    PERMANENT,
}

/*
 * One row of the frozen §4.6 table ("现象 → `FailureClass` → `FailoverPolicy` 动作").
 *
 * [steps] is the recipe in order. The policy walks it with the failure ordinal: attempt 1 plays
 * step 0, attempt 2 plays step 1, and later attempts stay on the last step. That is exactly how
 * `RetrySame(≤1) → SwitchTo(next)`, `Backoff(1s) → RetrySame(≤1) → SwitchTo(next)` and
 * `ResolveFresh → GiveUp` are written in the doc, so the table below is the doc transcribed.
 *
 * [logCode] is the event code the caller logs when it applies the row. It is always an
 * `EventCodes` constant — P1-6 invents no codes (docs/03 §3.3, and the P0-7 CI guard).
 */
data class FailureClassPlan(
    val failure: FailureClass,
    val phenomenon: String,
    val retryable: Boolean,
    val steps: List<FailoverStep>,
    val demotion: StreamDemotion = StreamDemotion.NONE,
    val demoteCodecCombination: Boolean = false,
    val retryWithoutPassthrough: Boolean = false,
    val emitsFailoverEvent: Boolean = true,
    val userMessage: String? = null,
    val logCode: String,
)

/*
 * The `FailureClass` → action table of docs/02 §4.6, as data.
 *
 * Keeping it as a table (instead of a `when` inside the policy) is what makes the doc auditable:
 * `FailureClassPolicyTest` asserts every enum constant has a row and that the rows carry the
 * doc's `retryable` values, so a future edit to the enum cannot silently fall through to a default.
 */
object FailurePolicies {

    private val rows: List<FailureClassPlan> = listOf(
        FailureClassPlan(
            failure = FailureClass.HTTP_CLIENT,
            phenomenon = "HTTP 403 / 404 / 410",
            retryable = false,
            steps = listOf(FailoverStep.SWITCH_NEXT),
            demotion = StreamDemotion.PERMANENT,
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.HTTP_SERVER,
            phenomenon = "HTTP 429 / 5xx",
            retryable = true,
            steps = listOf(FailoverStep.BACKOFF, FailoverStep.RETRY_SAME, FailoverStep.SWITCH_NEXT),
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.TIMEOUT,
            phenomenon = "连接/读取超时",
            retryable = true,
            steps = listOf(FailoverStep.RETRY_SAME, FailoverStep.SWITCH_NEXT),
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.NET_UNREACHABLE,
            phenomenon = "DNS 失败 / 网络不可达",
            retryable = true,
            steps = listOf(FailoverStep.SWITCH_NEXT),
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.TLS,
            phenomenon = "TLS 握手失败",
            retryable = false,
            steps = listOf(FailoverStep.SWITCH_NEXT),
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.PARSE,
            phenomenon = "清单/EPG 解析失败",
            retryable = false,
            steps = listOf(FailoverStep.SWITCH_NEXT),
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.DECODE_UNSUPPORTED,
            phenomenon = "解码器不支持该编码",
            retryable = false,
            steps = listOf(FailoverStep.SWITCH_NEXT),
            demoteCodecCombination = true,
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.DECODE_CORRUPT,
            phenomenon = "解码中断/花屏（流损坏）",
            retryable = true,
            steps = listOf(FailoverStep.RETRY_SAME, FailoverStep.SWITCH_NEXT),
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.EMPTY_MEDIA,
            phenomenon = "清单为空 / 无分片",
            retryable = false,
            steps = listOf(FailoverStep.SWITCH_NEXT),
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.PLAYLIST_GONE,
            phenomenon = "频道所有候选均失效",
            retryable = false,
            steps = listOf(FailoverStep.RESOLVE_FRESH, FailoverStep.GIVE_UP),
            userMessage = "该频道暂时不可用",
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.NO_CAPABILITY,
            phenomenon = "设备不支持（无解码器/无音轨）",
            retryable = false,
            steps = listOf(FailoverStep.RETRY_SAME, FailoverStep.SWITCH_NEXT),
            retryWithoutPassthrough = true,
            logCode = EventCodes.PLAY_FAILOVER,
        ),
        FailureClassPlan(
            failure = FailureClass.STORAGE,
            phenomenon = "数据库/存储写入失败",
            retryable = true,
            steps = emptyList(),
            emitsFailoverEvent = false,
            userMessage = "存储不可用，本次改动不会保存",
            logCode = EventCodes.DB_FAIL,
        ),
        FailureClassPlan(
            failure = FailureClass.PERMISSION,
            phenomenon = "SAF/存储权限缺失",
            retryable = false,
            steps = emptyList(),
            emitsFailoverEvent = false,
            userMessage = "请授予存储权限",
            logCode = EventCodes.PLAY_PREPARE_FAIL,
        ),
        FailureClassPlan(
            failure = FailureClass.CANCELLED,
            phenomenon = "协程取消",
            retryable = false,
            steps = emptyList(),
            emitsFailoverEvent = false,
            logCode = EventCodes.PLAY_END,
        ),
        FailureClassPlan(
            failure = FailureClass.UNKNOWN,
            phenomenon = "其他",
            retryable = false,
            steps = listOf(FailoverStep.SWITCH_NEXT),
            logCode = EventCodes.PLAY_FAILOVER,
        ),
    )

    private val byClass: Map<FailureClass, FailureClassPlan> = rows.associateBy { it.failure }

    init {
        val missing = FailureClass.entries.filterNot { it in byClass }
        require(missing.isEmpty()) { "docs/02 §4.6 has no fail-over row for: $missing" }
    }

    /** The frozen §4.6 row for [failure]. Throws only if the enum grew without a row. */
    fun plan(failure: FailureClass): FailureClassPlan =
        byClass[failure] ?: error("docs/02 §4.6 has no fail-over row for $failure")

    /** Every row, in enum order — the audit surface for the doc/test comparison. */
    fun all(): List<FailureClassPlan> = FailureClass.entries.map { plan(it) }

    /**
     * True when a failure of this class must produce a `PLAY_FAILOVER` event. `STORAGE`,
     * `PERMISSION` and `CANCELLED` must **not** (docs/02 §4.6: 不切换/不重试/不算失败).
     */
    fun emitsFailoverEvent(failure: FailureClass): Boolean = plan(failure).emitsFailoverEvent
}
