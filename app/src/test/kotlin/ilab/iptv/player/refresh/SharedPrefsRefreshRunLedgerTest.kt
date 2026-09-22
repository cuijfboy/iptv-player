package ilab.iptv.player.refresh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Card NEW-004 (docs/05-过程记录/67): the mark that tells "the process was killed" apart from "the
 * worker asked for a retry" — proven as a real `SharedPreferences` round trip, the same way
 * `SharedPrefsOverscanStoreTest` proves the overscan step.
 *
 * The second test is the one that matters: a *new store instance* is what the next app start has, and
 * the whole point of the mark is that it survives the process that wrote it.
 */
@RunWith(AndroidJUnit4::class)
class SharedPrefsRefreshRunLedgerTest {

    private lateinit var context: Context

    @Before
    fun clearStore() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    @Test
    fun `a fresh install has no unconcluded run on record`() {
        assertThat(SharedPrefsRefreshRunLedger(context).isRunUnconcluded()).isFalse()
    }

    @Test
    fun `a started run stays on record for the next process`() {
        SharedPrefsRefreshRunLedger(context).markRunStarted()

        // Same file, new object — exactly what a relaunch after a process death reads.
        assertThat(SharedPrefsRefreshRunLedger(context).isRunUnconcluded()).isTrue()
    }

    @Test
    fun `a concluded run clears the mark`() {
        val ledger = SharedPrefsRefreshRunLedger(context)
        ledger.markRunStarted()
        ledger.markRunConcluded()

        assertThat(ledger.isRunUnconcluded()).isFalse()
        assertThat(SharedPrefsRefreshRunLedger(context).isRunUnconcluded()).isFalse()
    }

    private fun prefs() =
        context.getSharedPreferences(SharedPrefsRefreshRunLedger.FILE_NAME, Context.MODE_PRIVATE)
}
