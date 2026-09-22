package ilab.iptv.player.core.player

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.model.EngineCapability
import ilab.iptv.player.core.model.PlaybackEvent
import ilab.iptv.player.core.model.PlaybackRequest

/**
 * Turns engine events into the registered `PLAY_*` event codes of docs/03 §3.3.
 *
 * WHY THIS LIVES NEXT TO THE ENGINE: docs/02 §7.3/§7.7 make `PlaybackController` the ONLY emitter
 * of `PLAY_PREPARE_START` / `PLAY_FIRST_FRAME` / `PLAY_PREPARE_FAIL` / `PLAY_FAILOVER` /
 * `PLAY_STALL` / `PLAY_END` / `PLAY_ENGINE_*`, and the engine deliberately writes no logs of its
 * own. This class is that emitter, packaged so P1-4/P1-6 installs it instead of re-deriving the
 * `costMs` contract: the engine's `PlaybackEvent` carries the measurement, this class stamps the
 * code, level and fields. It is also what the on-device smoke harness (docs/05 §12) uses.
 *
 * Every field is redacted-safe: no URLs, only ids, codecs and timings (docs/03 §11).
 */
class PlaybackEventLogger(private val logger: Logger, private val sessionId: String) {

    fun onPrepareStart(engineId: String, request: PlaybackRequest, attempt: Int) {
        logger.i(
            LogCategory.PLAYER,
            EventCodes.PLAY_PREPARE_START,
            "prepare start",
            mapOf(
                "channelId" to request.channelId,
                "streamId" to request.stream.id,
                "engine" to engineId,
                "attempt" to attempt,
                "timeoutMs" to request.timeoutMs,
                "preferPassthrough" to request.preferPassthrough,
            ),
        )
    }

    fun onEngineInit(engineId: String, caps: Set<EngineCapability>) {
        logger.d(
            LogCategory.PLAYER,
            EventCodes.PLAY_ENGINE_INIT,
            "engine init",
            mapOf("engine" to engineId, "capabilities" to caps.map { it.name }.sorted()),
        )
    }

    fun onEngineRelease(engineId: String) {
        logger.d(
            LogCategory.PLAYER,
            EventCodes.PLAY_ENGINE_RELEASE,
            "engine release",
            mapOf("engine" to engineId),
        )
    }

    /** docs/02 §7.3: `costMs` = first frame − prepare start. S2's audio-path observation rides along. */
    fun onFirstFrame(engineId: String, event: PlaybackEvent.FirstFrame, snapshot: PlaybackSnapshot) {
        logger.i(
            LogCategory.PLAYER,
            EventCodes.PLAY_FIRST_FRAME,
            "first frame",
            mapOf(
                "costMs" to event.costMs,
                "engine" to engineId,
                "vcodec" to snapshot.videoCodec,
                "acodec" to snapshot.audioCodec,
                "w" to snapshot.width,
                "h" to snapshot.height,
                "audioPath" to snapshot.audioPath.name,
            ),
        )
    }

    fun onPrepareFail(engineId: String, error: AppError, attempt: Int) {
        logger.w(
            LogCategory.PLAYER,
            EventCodes.PLAY_PREPARE_FAIL,
            "prepare failed",
            mapOf(
                "engine" to engineId,
                "failure" to error.failure.name,
                "httpStatus" to error.httpStatus,
                "attempt" to attempt,
                "detail" to error.detail,
            ),
            error.cause,
        )
    }

    fun onStalled(engineId: String, event: PlaybackEvent.Stalled) {
        logger.w(
            LogCategory.PLAYER,
            EventCodes.PLAY_STALL,
            "playback stalled",
            mapOf("engine" to engineId, "stalledMs" to event.stalledMs, "positionMs" to event.positionMs),
        )
    }

    /**
     * A user track change (P3-3 items 1–2: audio-track switch, subtitle on/off/select).
     *
     * EVENT-CODE DECISION (P3-3, reported to god): `docs/03 §3.3` has no code for "the user changed a
     * track", and P3-3 forbids touching `docs/01–04` (and `EventCodes.ALL`/`EventCodesTest` are pinned
     * to the 50 registered codes). Of the registered `PLAY_*` codes, `PLAY_FIRST_FRAME` is the only
     * one whose field contract covers what actually changed — §7.6 ties the audio path (`acodec`,
     * `audioPath`) to that event, and a track switch can move playback from one decoder path to
     * another (e.g. AC3 passthrough → AAC PCM). The event is therefore re-used with
     * `reason=track-change` and a `kind` discriminator, and the log line says "track changed" so a
     * reader is never told a first frame arrived. A dedicated `PLAY_TRACK_SELECT` (50 → 51) is the
     * right fix and is proposed in the P3-2/P3-3 verification record §7.
     */
    fun onTrackSelected(kind: String, id: String?, label: String?, enabled: Boolean, audioCodec: String?) {
        logger.i(
            LogCategory.PLAYER,
            EventCodes.PLAY_FIRST_FRAME,
            "playback track changed",
            mapOf(
                "reason" to TRACK_CHANGE_REASON,
                "kind" to kind,
                "track" to id,
                "trackLabel" to label,
                "trackEnabled" to enabled,
                "acodec" to audioCodec,
                "sessionId" to sessionId,
            ),
        )
    }

    /** Routes the events that have a registered code. Returns true when the event was logged. */
    fun onEvent(engineId: String, event: PlaybackEvent, snapshot: PlaybackSnapshot): Boolean {
        return when (event) {
            is PlaybackEvent.FirstFrame -> {
                onFirstFrame(engineId, event, snapshot)
                true
            }

            is PlaybackEvent.Stalled -> {
                onStalled(engineId, event)
                true
            }

            is PlaybackEvent.Capabilities -> {
                onEngineInit(engineId, event.caps)
                true
            }

            // Everything else is the controller's call, and deliberately not auto-routed:
            // - `Error`: `PLAY_PREPARE_FAIL` (docs/03 §3.3) means "起播失败", so it is logged from the
            //   controller's own `prepare` result ([onPrepareFail]) — an error AFTER the first frame
            //   is a session failure, which P1-6 answers with `PLAY_FAILOVER`, not with a prepare code.
            // - `Prepared`/`AudioTracks`/`Ended` have no §3.3 code of their own either (`PLAY_END`
            //   belongs to the end of the session, not to one media item finishing).
            else -> false
        }
    }

    companion object {
        /** Discriminator of the re-used `PLAY_FIRST_FRAME` line; see [onTrackSelected]. */
        const val TRACK_CHANGE_REASON = "track-change"
    }
}
