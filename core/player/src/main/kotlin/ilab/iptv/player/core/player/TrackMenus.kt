package ilab.iptv.player.core.player

import ilab.iptv.player.core.model.AudioTrackInfo
import ilab.iptv.player.core.model.SubtitleTrackInfo

/**
 * The two trace-selection state machines of P3-3 items 1–2 (音轨切换 / 字幕开关).
 *
 * WHY PURE AND SEPARATE: the part worth testing is "which track does the next press land on, and is
 * the entry point even usable" — not `AlertDialog`. Keeping it out of the Activity means the same
 * decisions are proven on the JVM: a stream with one audio track has no switch entry, a subtitle menu
 * always starts with 「关闭」, and both ladders are stable for a remote that only has UP/DOWN.
 */

/** One row of an on-screen track menu: `id == null` is the subtitle menu's 「关闭」 row. */
data class TrackOption(val id: String?, val label: String, val selected: Boolean)

/**
 * Positive modulo. `Math.floorMod` would do, but it is API 24 and this module ships to API 21
 * (lint `NewApi` is an error here) — and a wrap is two lines.
 */
private fun wrap(value: Int, size: Int): Int {
    if (size <= 0) return 0
    val remainder = value % size
    return if (remainder < 0) remainder + size else remainder
}

/**
 * Audio-track selection (P3-3 item 1).
 *
 * [enabled] is false with fewer than two tracks: switching between one track is not a feature, and
 * the info bar must say so instead of opening an empty menu. Selection WRAPS: the list is two or
 * three entries long and a wrap costs one press, whereas a clamped end makes the user press the
 * other direction to get back.
 */
data class AudioTrackState(val tracks: List<AudioTrackInfo>, val selectedId: String?) {

    val enabled: Boolean get() = tracks.size > 1

    fun options(): List<TrackOption> = tracks.map {
        TrackOption(id = it.id, label = it.label, selected = it.id == effectiveSelectedId())
    }

    /** The row the menu should highlight: the reported selection, else the first entry. */
    fun effectiveSelectedId(): String? =
        selectedId?.takeIf { id -> tracks.any { it.id == id } } ?: tracks.firstOrNull()?.id

    /** UP/DOWN inside the menu. */
    fun move(delta: Int): AudioTrackState {
        if (tracks.isEmpty()) return this
        val current = tracks.indexOfFirst { it.id == effectiveSelectedId() }.coerceAtLeast(0)
        val next = wrap(current + delta, tracks.size)
        return copy(selectedId = tracks[next].id)
    }

    /** OK on a row; an unknown id changes nothing. */
    fun select(id: String?): AudioTrackState =
        if (id == null || tracks.any { it.id == id }) copy(selectedId = id) else this

    /**
     * What the info-bar button shows behind its "音轨：" prefix: the chosen track's label, or "1 条"
     * when there is nothing to choose, or "无" when the stream reported no track at all.
     */
    fun valueLabel(): String {
        val chosen = tracks.firstOrNull { it.id == effectiveSelectedId() } ?: return "无"
        return if (enabled) chosen.label else "1 条"
    }
}

/**
 * Subtitle selection (P3-3 item 2): 「关闭」 plus one row per text track, in one ladder.
 *
 * [available] false means the stream carries no text track at all — the info bar greys the entry
 * point and explains it, per the P3-3 wording "无可选时入口置灰并说明". Off is a real state that is
 * shown as such ("字幕：关"), because "we found subtitles and they are off" is not "no subtitles".
 */
data class SubtitleTrackState(
    val tracks: List<SubtitleTrackInfo>,
    val selectedId: String?,
    val enabled: Boolean,
) {

    val available: Boolean get() = tracks.isNotEmpty()

    fun options(): List<TrackOption> = buildList {
        add(TrackOption(id = null, label = "关闭", selected = !enabled))
        tracks.forEach { track ->
            add(
                TrackOption(
                    id = track.id,
                    label = track.label,
                    selected = enabled && track.id == selectedId,
                ),
            )
        }
    }

    /** Ladder index: 0 = 关闭, 1..n = the tracks. */
    private fun index(): Int {
        if (!enabled) return 0
        val found = tracks.indexOfFirst { it.id == selectedId }
        return if (found < 0) 0 else found + 1
    }

    /** UP/DOWN inside the menu; wraps through 「关闭」. */
    fun move(delta: Int): SubtitleTrackState {
        if (!available) return this
        val count = tracks.size + 1
        val next = wrap(index() + delta, count)
        return if (next == 0) copy(enabled = false) else copy(enabled = true, selectedId = tracks[next - 1].id)
    }

    /** OK on a row: null = 「关闭」. */
    fun select(id: String?): SubtitleTrackState = when {
        id == null -> copy(enabled = false)
        tracks.any { it.id == id } -> copy(enabled = true, selectedId = id)
        else -> this
    }

    /**
     * What the info-bar button shows behind its "字幕：" prefix: "无" when the stream carries no text
     * track, "关" when the user switched them off, "开" when the chosen track is not in the list any
     * more, else the track's label.
     */
    fun valueLabel(): String {
        if (!available) return "无"
        if (!enabled) return "关"
        val chosen = tracks.firstOrNull { it.id == selectedId } ?: return "开"
        return chosen.label
    }
}
