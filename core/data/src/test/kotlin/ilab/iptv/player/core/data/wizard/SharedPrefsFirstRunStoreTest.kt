package ilab.iptv.player.core.data.wizard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.wizard.WizardState
import ilab.iptv.player.core.domain.wizard.WizardStep
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/04 P2-9 item 1: the "向导已完成" setting, proven by a real `SharedPreferences` round trip
 * rather than by asserting that a setter was called.
 *
 * Two claims matter here and nowhere else:
 * 1. a fresh install answers "not completed", which is what sends it to the wizard;
 * 2. the answer survives a new store instance, which is what a *restart* is — the launcher constructs
 *    a new object on every cold start, and a flag that only lived in memory would re-run the wizard
 *    forever.
 */
@RunWith(AndroidJUnit4::class)
class SharedPrefsFirstRunStoreTest {

    private lateinit var context: Context

    @Before
    fun clearStore() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    @Test
    fun `a fresh install has not completed the wizard`() {
        assertThat(SharedPrefsFirstRunStore(context).isCompleted()).isFalse()
    }

    @Test
    fun `the flag survives a restart`() {
        SharedPrefsFirstRunStore(context).markCompleted()

        // A new instance is what the next app start has: same file, new object.
        assertThat(SharedPrefsFirstRunStore(context).isCompleted()).isTrue()
    }

    @Test
    fun `marking completion twice is idempotent`() {
        val store = SharedPrefsFirstRunStore(context)
        store.markCompleted()
        store.markCompleted()

        assertThat(SharedPrefsFirstRunStore(context).isCompleted()).isTrue()
    }

    @Test
    fun `a hand-edited preference file cannot make an unset flag read as set`() {
        // The key is what `markCompleted` writes; anything else in the file is not the flag.
        prefs().edit().putBoolean("wizard-completed-typo", true).commit()

        assertThat(SharedPrefsFirstRunStore(context).isCompleted()).isFalse()
    }

    // ---- NEW-1: the step an abandoned run stopped on -------------------------------------------

    @Test
    fun `a fresh install has no step to resume`() {
        assertThat(SharedPrefsFirstRunStore(context).savedProgress()).isNull()
    }

    @Test
    fun `the saved step and its skips survive a restart`() {
        SharedPrefsFirstRunStore(context)
            .saveProgress(WizardState(step = WizardStep.WATCH, skipped = setOf(WizardStep.UPDATE)))

        // A new instance is what the next app start has.
        val saved = SharedPrefsFirstRunStore(context).savedProgress()

        assertThat(saved?.step).isEqualTo(WizardStep.WATCH)
        assertThat(saved?.skipped).containsExactly(WizardStep.UPDATE)
    }

    @Test
    fun `finishing the wizard clears the step it could otherwise resume`() {
        val store = SharedPrefsFirstRunStore(context)
        store.saveProgress(WizardState(step = WizardStep.UPDATE))

        store.markCompleted()

        assertThat(store.isCompleted()).isTrue()
        assertThat(SharedPrefsFirstRunStore(context).savedProgress()).isNull()
    }

    @Test
    fun `finishing does not clear a flag an earlier run already set`() {
        // The two keys are independent: writing progress must never be read as completion either.
        val store = SharedPrefsFirstRunStore(context)
        store.saveProgress(WizardState(step = WizardStep.SOURCE))

        assertThat(store.isCompleted()).isFalse()
    }

    @Test
    fun `a step name this build does not know reads as no progress`() {
        // Forward/backward compatibility: an unknown value must send the wizard to step 1, never to a
        // panel it cannot render.
        prefs().edit().putString("wizard-step", "SOMETHING_NEW").commit()

        assertThat(SharedPrefsFirstRunStore(context).savedProgress()).isNull()
    }

    @Test
    fun `the hand-over state is not a resumable step`() {
        val store = SharedPrefsFirstRunStore(context)
        store.saveProgress(WizardState(step = WizardStep.FINISHED))

        assertThat(store.savedProgress()).isNull()
    }

    @Test
    fun `an unknown name inside the skipped set is dropped, not guessed`() {
        prefs().edit()
            .putString("wizard-step", WizardStep.UPDATE.name)
            .putStringSet("wizard-skipped", setOf(WizardStep.SOURCE.name, "SOMETHING_NEW"))
            .commit()

        assertThat(SharedPrefsFirstRunStore(context).savedProgress()?.skipped)
            .containsExactly(WizardStep.SOURCE)
    }

    private fun prefs() = context.getSharedPreferences("first-run", Context.MODE_PRIVATE)
}
