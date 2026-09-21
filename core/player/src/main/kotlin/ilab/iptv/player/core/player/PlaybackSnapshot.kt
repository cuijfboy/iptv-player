package ilab.iptv.player.core.player

/**
 * Thread-safe read of "what is playing right now", cached by the engine on its own thread so the
 * controller (and the telemetry bridge) can enrich an event without touching the player.
 */
data class PlaybackSnapshot(
    val videoCodec: String?,
    val audioCodec: String?,
    val width: Int,
    val height: Int,
    val audioPath: AudioPath,
) {
    companion object {
        val EMPTY = PlaybackSnapshot(null, null, 0, 0, AudioPath.NONE)
    }
}
