package ilab.iptv.player.core.data.wizard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
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

    private fun prefs() = context.getSharedPreferences("first-run", Context.MODE_PRIVATE)
}
