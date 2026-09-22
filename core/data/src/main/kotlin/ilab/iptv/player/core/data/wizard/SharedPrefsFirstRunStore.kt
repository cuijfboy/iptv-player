package ilab.iptv.player.core.data.wizard

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ilab.iptv.player.core.domain.wizard.FirstRunStore
import ilab.iptv.player.core.domain.wizard.WizardState
import ilab.iptv.player.core.domain.wizard.WizardStep
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "wizard already handled" flag, in a one-file `SharedPreferences` store (docs/04 P2-9 item 1).
 *
 * **Why `SharedPreferences` and not Room/DataStore.** The value is one boolean read on the
 * cold-start path before the app knows which activity to open
 * ([ilab.iptv.player.core.domain.wizard.FirstRunGate]):
 * - `SharedPreferences` answers it with a synchronous in-memory read after the first load, so the
 *   router never needs a loading frame;
 * - DataStore is suspend-only, which would put an `await` between `onCreate` and the first screen for
 *   a flag whose whole purpose is to be decided instantly;
 * - a Room table would be a schema change (docs/02 §5.1) for a single flag that belongs to no entity,
 *   and `:core:domain`'s `FirstRunStore` interface keeps the choice swappable.
 *
 * This is the same trade `:core:player`'s `SharedPrefsOverscanStore` (P3-3) already made for its
 * display setting, and it is the pattern this file follows.
 *
 * `commit()` is deliberately not used: `apply()` writes to memory immediately (so a second read in
 * the same process — the router is re-created on every launch) and to disk asynchronously, which is
 * exactly the visibility the flag needs.
 */
@Singleton
class SharedPrefsFirstRunStore @Inject constructor(
    @ApplicationContext context: Context,
) : FirstRunStore {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun isCompleted(): Boolean = prefs.getBoolean(KEY_COMPLETED, false)

    override fun markCompleted() {
        // The completion flag and the saved step are mutually exclusive: a completed wizard has
        // nothing to resume, and a half-finished run must not look completed.
        prefs.edit()
            .putBoolean(KEY_COMPLETED, true)
            .remove(KEY_STEP)
            .remove(KEY_SKIPPED)
            .apply()
    }

    /**
     * NEW-1: the step the user left on. The step is stored by [WizardStep.name] and read back through
     * the enum, so a value an older/newer build wrote is ignored rather than mis-restored — an
     * unknown or terminal step reads as "no progress", which is the safe direction (the wizard starts
     * at step 1 rather than landing on a step it cannot render).
     */
    override fun savedProgress(): WizardState? {
        val step = stepByName(prefs.getString(KEY_STEP, null)) ?: return null
        val skipped = prefs.getStringSet(KEY_SKIPPED, emptySet()).orEmpty()
            .mapNotNull(::stepByName)
            .toSet()
        return WizardState(step = step, skipped = skipped)
    }

    override fun saveProgress(state: WizardState) {
        // FINISHED is the hand-over state, not a screen: writing it would make the next launch resume
        // a step that does not exist.
        if (state.step == WizardStep.FINISHED) return
        prefs.edit()
            .putString(KEY_STEP, state.step.name)
            .putStringSet(KEY_SKIPPED, state.skipped.map { it.name }.toSet())
            .apply()
    }

    private fun stepByName(name: String?): WizardStep? = when (name) {
        WizardStep.SOURCE.name -> WizardStep.SOURCE
        WizardStep.UPDATE.name -> WizardStep.UPDATE
        WizardStep.WATCH.name -> WizardStep.WATCH
        else -> null
    }

    private companion object {
        const val FILE_NAME = "first-run"
        const val KEY_COMPLETED = "wizard-completed"

        /** NEW-1: where the abandoned run stopped, so the next launch resumes there. */
        const val KEY_STEP = "wizard-step"
        const val KEY_SKIPPED = "wizard-skipped"
    }
}
