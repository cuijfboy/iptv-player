package ilab.iptv.player.core.player

/**
 * The overscan fine-tune of docs/02 §7.4 / docs/04 P3-3 ("过扫描微调…持久化").
 *
 * WHAT IT IS FOR: the four display modes of §7.4 change *how* the frame is fitted; this is the
 * ±step that both real TVs and old HDMI chains still need, because a panel can crop a few percent of
 * the picture (or show a black rim) no matter what aspect mode is selected. It is a scale factor
 * applied by the screen to the video container, **not** an engine parameter and **not** a fifth
 * aspect mode — §7.4 says overscan acts on the outer container, and P3-3 says it must not collide
 * with the LEFT/RIGHT aspect cycle.
 *
 * Pure (no Android, no media3) so the whole ladder, its clamp behaviour and the factor the View
 * applies are pinned by unit tests instead of by squinting at a TV.
 */
object OverscanPolicy {

    /**
     * The ladder, in percent of the video container's size. 100 % is the neutral step; below 100 the
     * picture is pulled in (a black rim becomes visible), above 100 it is pushed out (the panel's
     * crop is filled). 7 steps × 2 % keeps every press visible on a 1080p panel (~21 px) while the
     * ends stay far away from "unwatchable".
     */
    val PERCENTS: List<Int> = listOf(94, 96, 98, 100, 102, 104, 106)

    /** Index of 100 % in [PERCENTS] — the value a fresh install and "恢复标准" both use. */
    const val DEFAULT_INDEX: Int = 3

    /** Any stored/typed index lands on a real step; never throws, never wraps. */
    fun clamp(index: Int): Int = index.coerceIn(0, PERCENTS.size - 1)

    /**
     * One step up ([delta] > 0) or down ([delta] < 0).
     *
     * The ends CLAMP instead of wrapping: a step ladder that jumps from +6 % to −6 % turns "one more
     * press" into a large, surprising change, and the user picked the end deliberately.
     */
    fun move(index: Int, delta: Int): Int = clamp(clamp(index) + delta)

    fun percent(index: Int): Int = PERCENTS[clamp(index)]

    /** What the player screen feeds `View.setScaleX/Y`; 1.0 = untouched. */
    fun scale(index: Int): Float = percent(index) / 100f

    /** On-screen label ("过扫描：100%"), the same string the settings row shows. */
    fun label(index: Int): String = "${percent(index)}%"

    fun isDefault(index: Int): Boolean = clamp(index) == DEFAULT_INDEX
}
