package ilab.iptv.player.core.ui.wizard

import android.content.Context
import android.content.Intent

/**
 * The navigation contract for the P2-9 first-run wizard, the same shape as `BrowseContract` and
 * `PlayerContract`: an action string plus its extras in `:core:ui`, so `:app`'s launcher can open a
 * screen that lives in `:feature:wizard` without depending on its class (docs/02 §3.2 forbids
 * feature → feature, and the launcher should not hard-wire one either).
 *
 * **Only the launcher opens the wizard, and only when the first-run gate says so**
 * (`FirstRunGate`). There is no in-app "show the wizard again" entry on purpose: docs/02 §8.1 marks
 * the wizard 仅首次, and a settings row that re-runs it would need its own reset semantics (what
 * happens to the channels, the subscriptions and the flag) which no card defines yet.
 */
object WizardContract {

    const val ACTION_WIZARD = "ilab.iptv.player.action.WIZARD"

    /** Why the wizard is being shown; carried for the log line, not for the behaviour. */
    const val EXTRA_REASON = "ilab.iptv.player.extra.WIZARD_REASON"

    /** The only reason that exists today: the first-run gate sent the user here. */
    const val REASON_FIRST_RUN = "first-run"

    fun intent(context: Context, reason: String = REASON_FIRST_RUN): Intent =
        Intent(ACTION_WIZARD)
            .setPackage(context.packageName)
            .putExtra(EXTRA_REASON, reason)

    fun readReason(intent: Intent?): String =
        intent?.getStringExtra(EXTRA_REASON) ?: REASON_FIRST_RUN
}
