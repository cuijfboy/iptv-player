package ilab.iptv.player.core.design

/**
 * The focus palette as plain ARGB ints (P3-7 item 2, docs/02 §8.2).
 *
 * Why this exists next to `values/colors.xml`: the focus language has to be **one** language on every
 * screen, and the EPG grid draws its focus in a pure-Kotlin renderer
 * (`:feature:epg .../grid/EpgGridRenderer.kt`) that deliberately cannot touch `android.graphics` or
 * read resources, so it takes colors as ints. Without a shared constant the grid's palette and the
 * XML selectors would be two independent guesses at "the accent".
 *
 * The values must equal `core/design/src/main/res/values/colors.xml`; [TvFocusTokenTest] parses that
 * file and fails if they drift, so "unified" is checkable instead of a claim.
 */
object TvFocus {

    /** The accent stroke. `@color/focus_stroke`. */
    val STROKE: Int = 0xFF7AC7FF.toInt()

    /** Focused row/key/tile fill. `@color/focus_fill`. */
    val FILL: Int = 0xFF2B2B3C.toInt()

    /** Pressed (still unfocused) fill. `@color/focus_fill_pressed`. */
    val FILL_PRESSED: Int = 0xFF35354A.toInt()

    /** The player's warm over-video fill. `@color/focus_fill_over_video`. */
    val FILL_OVER_VIDEO: Int = 0xFFF0C674.toInt()

    /** Stroke width in dp. `@dimen/focus_stroke_width`. */
    const val STROKE_WIDTH_DP: Int = 2
}
