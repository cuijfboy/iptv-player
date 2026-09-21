package ilab.iptv.player.feature.player

import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.player.AspectRatioPlan
import ilab.iptv.player.core.player.ScaleMode

/**
 * The four display modes of docs/02 §7.4 as one remote-reachable cycle, plus the view constants the
 * player screen applies.
 *
 * The mapping itself is NOT duplicated here: [AspectRatioPlan] (in `:core:player`, already unit
 * tested) is the single source of the mode → scale behaviour, and [viewMode] only widens that plan
 * into media3-ui's `AspectRatioFrameLayout` resize constants — the one place a View needs a number.
 */
object AspectRatioCycle {

    /** Order of the four modes as the remote cycles them (FIT first = the neutral default). */
    val ORDER: List<AspectRatioMode> = listOf(
        AspectRatioMode.FIT,
        AspectRatioMode.FILL,
        AspectRatioMode.ZOOM,
        AspectRatioMode.FIXED_4_3,
    )

    fun next(current: AspectRatioMode): AspectRatioMode {
        val index = ORDER.indexOf(current)
        return if (index < 0) ORDER.first() else ORDER[(index + 1) % ORDER.size]
    }

    /** Short label for the on-screen button, in the language of the product (docs/01). */
    fun label(mode: AspectRatioMode): String = when (mode) {
        AspectRatioMode.FIT -> "适应"
        AspectRatioMode.FILL -> "拉伸"
        AspectRatioMode.ZOOM -> "裁剪"
        AspectRatioMode.FIXED_4_3 -> "4:3"
    }

    /** What to feed `AspectRatioFrameLayout.setResizeMode` for [mode]. */
    fun resizeMode(mode: AspectRatioMode): Int = when (AspectRatioPlan.of(mode).scaleMode) {
        ScaleMode.FIT -> RESIZE_MODE_FIT
        ScaleMode.FILL -> RESIZE_MODE_FILL
        ScaleMode.ZOOM -> RESIZE_MODE_ZOOM
        ScaleMode.FIXED_ASPECT -> RESIZE_MODE_FIXED_WIDTH
    }

    /** `AspectRatioFrameLayout.setAspectRatio`, or null when the video's own ratio is used. */
    fun fixedAspectRatio(mode: AspectRatioMode): Float? = AspectRatioPlan.of(mode).fixedAspectRatio

    // Values of `androidx.media3.ui.AspectRatioFrameLayout` (RESIZE_MODE_FIT=0, FILL=3, ZOOM=4,
    // FIXED_WIDTH=1), repeated as constants so this mapping stays pure and unit-testable. If media3
    // ever changes them, the device check of docs/05 §16 fails loudly instead of silently letterboxing.
    const val RESIZE_MODE_FIT = 0
    const val RESIZE_MODE_FIXED_WIDTH = 1
    const val RESIZE_MODE_FILL = 3
    const val RESIZE_MODE_ZOOM = 4
}
