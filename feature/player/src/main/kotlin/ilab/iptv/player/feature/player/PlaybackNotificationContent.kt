package ilab.iptv.player.feature.player

import ilab.iptv.player.core.model.EngineState
import ilab.iptv.player.core.model.PlaybackPhase
import ilab.iptv.player.core.model.PlaybackUiState

/**
 * The text of the ongoing playback notification (P1-7 items 1 and 2).
 *
 * WHY IT IS PURE: "元数据至少给频道名" is the acceptance line, and the way it silently breaks is that a
 * later phase overwrites the title with a status line, or a state without a channel name still
 * publishes a notification. Both are table-driven decisions, so the table is a pure class and the
 * Android half ([PlaybackNotifications]) only paints what it returns.
 *
 * Returning `null` is a real answer: no channel (nothing was ever opened, or the catalog did not
 * resolve) means there is nothing honest to put on a notification, and the service must not show one.
 */
data class PlaybackNotificationContent(
    /** The channel the notification belongs to, so tapping it re-opens that channel (§8.1 contract). */
    val channelId: Long?,
    /** Always the channel name — the metadata requirement, never replaced by a status line. */
    val title: String,
    /** The status line under the title: quality, the fail-over hint, or the failure text. */
    val text: String?,
    /** `true` → the notification offers "pause"; `false` → it offers "play". */
    val showPauseAction: Boolean,
    /** Ongoing notifications are not dismissible while playback runs (the service is the owner). */
    val ongoing: Boolean,
) {
    companion object {

        const val PREPARING_TEXT = "正在起播…"
        const val BUFFERING_TEXT = "缓冲中…"
        const val PLAYING_TEXT = "正在播放"
        const val PAUSED_TEXT = "已暂停"
        const val FAILED_TEXT = "播放失败"
        const val PLACEHOLDER_TITLE = "IPTV 播放器"
        const val OPENING_TEXT = "正在打开频道…"

        /**
         * The notification the service must show the moment it is started, before the first playback
         * state exists. Android has no "started but silent" state for a foreground service: a
         * `startForegroundService` that does not post a notification within ~5 s is killed, so the
         * service posts this one and replaces it as soon as the channel is known.
         */
        val PLACEHOLDER = PlaybackNotificationContent(
            channelId = null,
            title = PLACEHOLDER_TITLE,
            text = OPENING_TEXT,
            showPauseAction = false,
            ongoing = true,
        )

        fun of(ui: PlaybackUiState, engineState: EngineState): PlaybackNotificationContent? {
            val channelName = ui.infoBar?.channelName?.takeIf { it.isNotBlank() } ?: return null
            val paused = isPaused(engineState, ui.phase)
            return PlaybackNotificationContent(
                channelId = ui.channelId,
                title = channelName,
                text = textOf(ui, paused),
                // The button is the inverse of what the engine is doing: a running stream gets
                // "pause", anything the user has paused gets "play".
                showPauseAction = !paused,
                ongoing = ui.phase != PlaybackPhase.ERROR,
            )
        }

        /**
         * "已暂停" is a real state of the TV remote (and of the system media control, P1-7 item 2), and
         * ExoPlayer reports it as `STATE_READY` + `isPlaying == false` — the same coarse engine state
         * a prepared-but-not-started stream has. Both are "not playing", which is what the
         * notification must say instead of claiming a buffer that is not filling.
         */
        fun isPaused(engineState: EngineState, phase: PlaybackPhase): Boolean = when (engineState) {
            EngineState.READY, EngineState.IDLE -> phase != PlaybackPhase.PREPARING
            else -> false
        }

        private fun textOf(ui: PlaybackUiState, paused: Boolean): String? = when {
            // The failure text wins over everything: it is the only line the user must act on.
            ui.phase == PlaybackPhase.ERROR -> ui.errorText ?: FAILED_TEXT
            // P1-5's fail-over hint ("正在切换备用源…" / "已切换备用源") is the state the user must see.
            ui.phase == PlaybackPhase.FAILOVER -> ui.infoBar?.failoverHint ?: HINT_SWITCHING
            ui.phase == PlaybackPhase.PREPARING -> PREPARING_TEXT
            paused -> listOfNotNull(PAUSED_TEXT, ui.infoBar?.qualityLabel).joinToString(" · ")
            ui.phase == PlaybackPhase.BUFFERING -> ui.infoBar?.qualityLabel ?: BUFFERING_TEXT
            ui.phase == PlaybackPhase.PLAYING -> ui.infoBar?.qualityLabel ?: PLAYING_TEXT
            else -> ui.infoBar?.qualityLabel
        }
    }
}
