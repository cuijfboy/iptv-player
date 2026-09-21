package ilab.iptv.player.refresh

import ilab.iptv.player.core.model.RefreshPhase
import ilab.iptv.player.core.model.RefreshProgress

/**
 * The text and the progress bar of the refresh notification (docs/04 P2-5 item 2).
 *
 * WHY IT IS PURE: "展示进度百分比/阶段" is two decisions (which phase label, which percentage) and the
 * way it breaks is arithmetic — a `total` of 0 divides by zero, `done > total` reports 107 % — plus the
 * 不打扰 promise, which is a *content* property here (no sound, no vibration, never a new alert,
 * never `ongoing = false` while the work runs). The Android half ([RefreshNotifications]) only paints.
 */
data class RefreshNotificationContent(
    val title: String,
    val text: String?,
    /** `null` → an indeterminate bar (the phase has no item counter yet). */
    val percent: Int?,
    val ongoing: Boolean,
) {
    companion object {

        const val RUNNING_TITLE = "正在刷新频道源"
        const val DONE_TITLE = "频道源已更新"
        const val DEFERRED_TITLE = "刷新已推迟"
        const val FAILED_TITLE = "频道源刷新失败"

        const val STARTING_TEXT = "准备中…"
        const val DEFERRED_TEXT = "正在播放，稍后自动重试"
        const val FAILED_TEXT = "稍后会自动重试"

        /** The first frame the foreground service shows, before the pipeline emits anything. */
        fun starting(): RefreshNotificationContent = RefreshNotificationContent(
            title = RUNNING_TITLE,
            text = STARTING_TEXT,
            percent = null,
            ongoing = true,
        )

        fun of(progress: RefreshProgress): RefreshNotificationContent {
            val label = phaseLabel(progress.phase)
            val percent = percentOf(progress)
            return RefreshNotificationContent(
                title = RUNNING_TITLE,
                text = if (percent == null) label else "$label · $percent%",
                percent = percent,
                ongoing = true,
            )
        }

        fun done(): RefreshNotificationContent = RefreshNotificationContent(
            title = DONE_TITLE,
            text = null,
            percent = 100,
            ongoing = false,
        )

        fun deferred(): RefreshNotificationContent = RefreshNotificationContent(
            title = DEFERRED_TITLE,
            text = DEFERRED_TEXT,
            percent = null,
            ongoing = false,
        )

        fun failed(): RefreshNotificationContent = RefreshNotificationContent(
            title = FAILED_TITLE,
            text = FAILED_TEXT,
            percent = null,
            ongoing = false,
        )

        /**
         * `done / total` as a whole percentage, `null` when the phase has no denominator:
         * Fetch's first emission and the terminal `DONE` both report `total = 0` on some paths, and a
         * bar that jumps to 100 % at the start is worse than an indeterminate one.
         */
        fun percentOf(progress: RefreshProgress): Int? {
            if (progress.phase == RefreshPhase.DONE) return 100
            if (progress.total <= 0) return null
            val raw = progress.done * 100 / progress.total
            return raw.coerceIn(0, 100)
        }

        /** Chinese stage labels for the notification's second line (docs/02 §6.1 stage names). */
        fun phaseLabel(phase: RefreshPhase): String = when (phase) {
            RefreshPhase.FETCH -> "获取源"
            RefreshPhase.PARSE -> "解析清单"
            RefreshPhase.NORMALIZE -> "规范化"
            RefreshPhase.DEDUPE -> "去重"
            RefreshPhase.SHALLOW -> "轻量校验"
            RefreshPhase.DEEP -> "深度探测"
            RefreshPhase.SCORE -> "评分"
            RefreshPhase.SELECT -> "甄选"
            RefreshPhase.PERSIST -> "写入"
            RefreshPhase.DONE -> "完成"
        }
    }
}
