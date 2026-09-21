package ilab.iptv.player.feature.player

import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.player.AudioFocusDecision
import ilab.iptv.player.core.player.AudioFocusEvent

/**
 * Turns the P1-7 system-integration actions into the four key-path event codes of docs/03 §3.3.1.
 *
 * WHY A SEPARATE EMITTER (not `Log.d` calls inside the service): the same reasons as
 * [ilab.iptv.player.core.player.PlaybackEventLogger] — the code, the level and the field names are a
 * contract with the troubleshooting manual (docs/03 §12), so they live in one class with one set of
 * field names, and a JVM test can pin them. The service, the audio-focus callback and the
 * network-retry wiring all report through here.
 *
 * FIELDS (the "什么时候 / 为什么 / 结果如何" the work package asks for):
 *  - service start: `channelId`, `streamId`, `phase`, `result` (`ok` / `failed`); a failure also
 *    carries the exception. `result=failed` is how an Android 12 background-start rejection shows up
 *    in the log instead of vanishing with the service.
 *  - service stop: `reason`, `channelId`, `streamId`.
 *  - focus change: `reason` (the focus event), plus what the policy decided to do (`pause` /
 *    `resume` / `duck` / `restoreVolume` / `abandonFocus`).
 *  - network retry: `attempt` (the retry number for this session), `channelId`.
 *
 * Every field is redaction-safe: ids, phases, enum names and booleans — no URLs (docs/03 §11).
 */
class PlaybackSystemEvents(private val logger: Logger) {

    /** The service reached the foreground with its notification up. */
    fun playbackServiceStarted(channelId: Long?, streamId: Long?, phase: String) {
        logger.i(
            LogCategory.SERVICE,
            EventCodes.SERVICE_PLAYBACK_START,
            "playback service in foreground",
            mapOf(
                "channelId" to channelId,
                "streamId" to streamId,
                "phase" to phase,
                "result" to "ok",
            ),
        )
    }

    /**
     * The foreground transition failed (e.g. `ForegroundServiceStartNotAllowedException` on API 31+).
     * The notification never came up, so this is the only trace the app can leave.
     */
    fun playbackServiceStartFailed(phase: String, error: Throwable) {
        logger.e(
            LogCategory.SERVICE,
            EventCodes.SERVICE_PLAYBACK_START,
            "playback service foreground start failed",
            mapOf(
                "phase" to phase,
                "result" to "failed",
            ),
            error,
        )
    }

    /** The service left the foreground / stopped; [reason] says which path ended it. */
    fun playbackServiceStopped(reason: String, channelId: Long?, streamId: Long?) {
        logger.i(
            LogCategory.SERVICE,
            EventCodes.SERVICE_PLAYBACK_STOP,
            "playback service stopped",
            mapOf(
                "reason" to reason,
                "channelId" to channelId,
                "streamId" to streamId,
            ),
        )
    }

    /** One audio-focus change and what the policy answered: `reason` is the focus event itself. */
    fun focusChanged(event: AudioFocusEvent, decision: AudioFocusDecision) {
        logger.i(
            LogCategory.PLAYER,
            EventCodes.PLAY_FOCUS_CHANGE,
            "audio focus changed",
            mapOf(
                "reason" to event.name,
                "pause" to decision.pause,
                "resume" to decision.resume,
                "duck" to decision.duck,
                "restoreVolume" to decision.restoreVolume,
                "abandonFocus" to decision.abandonFocus,
            ),
        )
    }

    /** A retry the network coming back released: `attempt` is the retry number for this session. */
    fun networkRetry(attempt: Int, channelId: Long?) {
        logger.i(
            LogCategory.PLAYER,
            EventCodes.PLAY_NET_RETRY,
            "network restored, retrying channel",
            mapOf(
                "attempt" to attempt,
                "channelId" to channelId,
            ),
        )
    }
}
