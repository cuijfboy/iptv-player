package ilab.iptv.player.core.ui.back

/**
 * The app's BACK contract as data (P3-7 item 1, docs/02 §8.1 "返回键层级").
 *
 * The contract already existed, but it existed in four places at once: `docs/02 §8.1`'s table, a
 * per-screen inline `if` in the browse/player/search screens, and `SettingsHierarchy`. When the same
 * rule is written down four times, the screens drift — which is exactly what the P3-7 audit found
 * (the player's own comment claimed the failure card was a BACK level while the code only ever armed
 * the info bar). So the **screen stack** lives here, once, and every screen either reads it or is
 * asserted against it by a test.
 *
 * What is deliberately *not* here: the in-page levels a screen can be in (the player's info bar, the
 * browse screen's manage mode, the search screen's typed query). Those are per-screen state machines
 * — `PlayerBackPolicy`, `BrowseBackPolicy`, `SearchBackPolicy` — because they consume one BACK press
 * *before* the screen stack is asked to pop. This file answers only "what is the parent screen".
 */
enum class TvScreen {
    /** The root list. Its BACK leaves the app (docs/02 §8.1 "浏览页 → 系统"). */
    BROWSE,
    PLAYER,
    EPG_GRID,
    SEARCH,
    SETTINGS,
    DIAGNOSTICS,

    /** P0-6/P1-8 device & log console, reached from the settings page. */
    LOG_CONSOLE,

    /**
     * P2-6 source management. It has two entrances — the browse screen's shortcut button and the
     * settings page's "源管理" row — so its parent is whichever screen opened it; the stack (a
     * `startActivity`, never a re-parenting) already guarantees that. [parentOf] reports the
     * documentation-level parent (`BROWSE`), and `SourceManagementActivity` relies on `finish()`.
     */
    SOURCE_MANAGEMENT,
}

object BackHierarchy {

    /**
     * The screen BACK returns to, or `null` for the root of the app's own stack (browse). `null` is
     * the documented "returns to the system" case, not a missing value.
     *
     * Frozen by `docs/02 §8.1`; [BackHierarchyTest] asserts every entry, so this table and the
     * document cannot disagree.
     */
    fun parentOf(screen: TvScreen): TvScreen? = when (screen) {
        TvScreen.BROWSE -> null
        TvScreen.PLAYER -> TvScreen.BROWSE
        TvScreen.EPG_GRID -> TvScreen.BROWSE
        TvScreen.SEARCH -> TvScreen.BROWSE
        TvScreen.SETTINGS -> TvScreen.BROWSE
        TvScreen.SOURCE_MANAGEMENT -> TvScreen.BROWSE
        TvScreen.DIAGNOSTICS -> TvScreen.SETTINGS
        TvScreen.LOG_CONSOLE -> TvScreen.SETTINGS
    }

    /** True for the screen the user reaches by backing out of everything (browse). */
    fun isRoot(screen: TvScreen): Boolean = parentOf(screen) == null

    /**
     * The screens from [screen] down to the root, inclusive — the BACK walk a tester performs. Used
     * by the audit report and by the test that walks every screen up to the root.
     */
    fun stackFrom(screen: TvScreen): List<TvScreen> = buildList {
        var current: TvScreen? = screen
        while (current != null) {
            add(current)
            current = parentOf(current)
        }
    }
}
