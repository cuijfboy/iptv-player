package ilab.iptv.player.feature.player

/**
 * The info bar's show / auto-fade timer (P1-4 item 2: "任意遥控键唤出，5 s 无操作自动淡出").
 *
 * Pure and time-injected so the acceptance number is pinned by a test instead of by watching a
 * screen: [INFO_BAR_TIMEOUT_MS] is the constant the device check reads off the clock, and
 * [onKey] restarts it, which is what "无操作" means — a key is activity, so the bar stays.
 *
 * The caller owns the clock (`SystemClock.uptimeMillis()` on device) and calls [tick]; [tick]
 * reports the fade exactly once, so the animation cannot be started twice by a late callback.
 */
class InfoBarVisibility(private val timeoutMs: Long = INFO_BAR_TIMEOUT_MS) {

    var visible: Boolean = false
        private set

    /** Uptime at which the bar starts fading out; only meaningful while [visible]. */
    var hideAtMs: Long = 0L
        private set

    /** Any remote key: show the bar, or keep it up and restart the countdown. */
    fun onKey(nowMs: Long): Boolean {
        visible = true
        hideAtMs = nowMs + timeoutMs
        return true
    }

    /** Defensive re-arm: same as [onKey] without claiming a key event happened. */
    fun show(nowMs: Long) {
        onKey(nowMs)
    }

    /** Hide immediately (back key of the two-level back, or the screen going away). */
    fun hide() {
        visible = false
    }

    /** @return true when this call is the one that faded the bar out. */
    fun tick(nowMs: Long): Boolean {
        if (!visible || nowMs < hideAtMs) return false
        visible = false
        return true
    }

    companion object {
        /** P1-4 item 2: frozen auto-fade window, the number the device check measures. */
        const val INFO_BAR_TIMEOUT_MS = 5_000L

        /** How long the fade itself takes (§8.2 wants it visible enough to read, not a jump cut). */
        const val INFO_BAR_FADE_MS = 300L
    }
}
