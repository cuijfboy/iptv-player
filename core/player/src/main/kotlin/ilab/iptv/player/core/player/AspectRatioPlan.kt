package ilab.iptv.player.core.player

import ilab.iptv.player.core.model.AspectRatioMode

/**
 * How a scale mode turns into view behaviour. Deliberately media3-ui-free so it can be unit-tested
 * and so the exact constant mapping is applied by whichever view the UI uses (P1-4).
 */
enum class ScaleMode {
    /** Letterbox: the whole frame, bars where the aspect differs. */
    FIT,

    /** Stretch to fill the view, ignoring the source aspect. */
    FILL,

    /** Fill the view and crop the overflow. */
    ZOOM,

    /** Letterbox a fixed aspect ratio rather than the video's own. */
    FIXED_ASPECT,
}

data class AspectRatioPlan(val scaleMode: ScaleMode, val fixedAspectRatio: Float?) {
    companion object {
        /** 4:3 — the one fixed ratio the frozen four-mode list names (docs/02 §7.4). */
        const val FOUR_THREE = 4f / 3f

        fun of(mode: AspectRatioMode): AspectRatioPlan = when (mode) {
            AspectRatioMode.FIT -> AspectRatioPlan(ScaleMode.FIT, null)
            AspectRatioMode.FILL -> AspectRatioPlan(ScaleMode.FILL, null)
            AspectRatioMode.ZOOM -> AspectRatioPlan(ScaleMode.ZOOM, null)
            AspectRatioMode.FIXED_4_3 -> AspectRatioPlan(ScaleMode.FIXED_ASPECT, FOUR_THREE)
        }
    }
}
