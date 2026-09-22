package ilab.iptv.player.feature.player

/** What one BACK press should do (docs/02 §8.1 返回键层级、§8.2 `KEYCODE_BACK`=层级回退). */
sealed interface BackAction {

    /** First level: the info bar (or any overlay) was up — take it down and stay in playback. */
    data object HideOverlay : BackAction

    /** Second level: nothing left to dismiss — leave the player screen, back to the list. */
    data object ExitPlayer : BackAction
}

/**
 * The two-level back state machine of P1-4 item 4 ("信息条显示时返回=收起信息条，再返回=退出播放").
 *
 * It is a state machine and not a one-liner over `infoBar.visible`, because the rule is about what the
 * user has already dismissed: after the first BACK the screen is immersive even if the bar blinks back
 * on. Keeping the state here means the two halves of BACK cannot drift apart, and the sequence is
 * unit-tested end to end.
 *
 * P3-7 item 1 (audit correction): the level is **the info bar**, and only the info bar. This class
 * used to claim in a comment that the failure overlay was "equally a level", while nothing in
 * `PlayerActivity` ever armed one — the code and its own documentation disagreed. `docs/02 §8.2`
 * freezes the rule as "信息条可见 → 先收起信息条；再按 → 回浏览页", and the failure card is not
 * dismissible at all (it is a status the screen is in, with 重试 on it), so a BACK that lands while
 * the card is up and the bar is down **leaves the player** — the honest reading of the frozen rule,
 * pinned by `PlayerBackPolicyTest`.
 */
class PlayerBackPolicy {

    /** What the player screen currently shows on top of the video. */
    enum class Level { IMMERSIVE, OVERLAY_VISIBLE }

    var level: Level = Level.IMMERSIVE
        private set

    /** Called whenever the info bar (or the failure overlay) becomes visible. */
    fun onOverlayShown(): Level {
        level = Level.OVERLAY_VISIBLE
        return level
    }

    /** Called when the bar/overlay goes away on its own (auto-fade) or is dismissed. */
    fun onOverlayHidden(): Level {
        level = Level.IMMERSIVE
        return level
    }

    /** One BACK press. */
    fun onBack(): BackAction = when (level) {
        Level.OVERLAY_VISIBLE -> {
            level = Level.IMMERSIVE
            BackAction.HideOverlay
        }

        Level.IMMERSIVE -> BackAction.ExitPlayer
    }
}
