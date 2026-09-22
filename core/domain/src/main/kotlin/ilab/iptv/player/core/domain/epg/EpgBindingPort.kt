package ilab.iptv.player.core.domain.epg

import ilab.iptv.player.core.model.EpgBindingReport

/**
 * The **read-only evidence entry** for the EPG coverage question (BUG-20260922-018):
 * "this row is blank on screen — is nothing bound to it, or is its guide id empty in these hours?"
 *
 * It exists because the build had no way to answer that per channel. The QA round could read the
 * aggregate `EPG_COVERAGE` numbers and could look at the grid, but nothing joined the two, so
 * "覆盖率数字 = 网格可渲染块数" could not be checked on the TV. This port is that join: one channel
 * per row, each with the id it is bound to, how many programmes that id holds inside the grid window,
 * and which match tier produced the binding.
 *
 * **Read only by construction.** The interface has one `suspend` read and no writer; the panel that
 * consumes it renders a snapshot and cannot change a binding, a guide id or a programme row. Manual
 * re-binding stays where it already is (P3-4's channel manager), so adding evidence does not add a
 * second way to edit EPG data.
 *
 * It lives in `:core:domain` — not in `:core:data` beside `EpgStoredGuideReader` — because the caller
 * is `:feature:settings`, and `docs/02 §3.2` rule 2 forbids a feature from seeing `:core:data`. The
 * window is not a parameter: the implementation derives it from the clock via `EpgGridWindow`, the
 * same definition the grid opens on and the coverage event counts with, so a reader cannot ask this
 * question about a different window than the one the grid draws.
 */
interface EpgBindingPort {

    /** Every channel's binding and its programme count inside the grid window, right now. */
    suspend fun bindingReport(): EpgBindingReport
}
