package ilab.iptv.player.feature.player

import ilab.iptv.player.core.model.NowNext

/**
 * Which EPG line the info bar shows, and what it says (P2-7 item 4).
 *
 * The presentation decision is split out of [PlayerActivity] because it is the part with rules in it,
 * and the rules are worth testing without a device:
 *
 * - **"now" wins over "next".** The bar is one line, and while something is on, that is the answer to
 *   "what am I watching".
 * - **`next` alone is shown** when the guide has the following programme but not the current one
 *   (a slot that started before the guide's coverage, or a channel that dropped out mid-programme).
 *   Showing nothing there would hide data we actually have.
 * - **nothing is shown** when neither exists — [Kind.NONE], which the view renders by taking the line
 *   down rather than printing a placeholder. A placeholder that says "P2-7" is scaffolding; on screen
 *   it is noise, and a channel with no EPG is a normal state (docs/02 §6.3 降级: "UI 只显示频道名").
 */
object NowNextLabel {

    enum class Kind { NOW, NEXT, NONE }

    data class Line(val kind: Kind, val title: String?) {
        companion object {
            val NONE = Line(Kind.NONE, null)
        }
    }

    fun of(nowNext: NowNext?): Line {
        val now = nowNext?.now?.title?.takeIf { it.isNotBlank() }
        if (now != null) return Line(Kind.NOW, now)
        val next = nowNext?.next?.title?.takeIf { it.isNotBlank() }
        if (next != null) return Line(Kind.NEXT, next)
        return Line.NONE
    }
}
