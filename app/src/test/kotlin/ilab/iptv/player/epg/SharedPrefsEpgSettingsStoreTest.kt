package ilab.iptv.player.epg

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Card EPG-SETTINGS-1: the switch and the freshness threshold are persisted, so a change survives a
 * restart — proven as a real `SharedPreferences` round trip, the same way
 * `SharedPrefsRefreshRunLedgerTest` proves the run ledger.
 *
 * The second test is the one that matters: a *new store instance* is what the next app start has, and
 * the whole point of the setting is that it outlives the process that wrote it. The coercion test pins
 * requirement 4's "非法值按既有 coerceIn 口径夹取": an out-of-range interval is clamped, never stored raw.
 */
@RunWith(AndroidJUnit4::class)
class SharedPrefsEpgSettingsStoreTest {

    private lateinit var context: Context

    @Before
    fun clearStore() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SharedPrefsEpgSettingsStore.FILE_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `a fresh install has the frozen defaults`() {
        val settings = SharedPrefsEpgSettingsStore(context).read()

        assertThat(settings.enabled).isTrue()
        assertThat(settings.minIntervalMs).isEqualTo(EpgRefreshSettings.DEFAULT_MIN_INTERVAL_MS)
        assertThat(settings.emptyRetryMs).isEqualTo(EpgRefreshSettings.DEFAULT_EMPTY_RETRY_MS)
    }

    @Test
    fun `a change stays on record for the next process`() {
        SharedPrefsEpgSettingsStore(context).write(
            EpgRefreshSettings(enabled = false, minIntervalMs = 60 * 60_000L),
        )

        // Same file, new object — exactly what a relaunch reads.
        val reread = SharedPrefsEpgSettingsStore(context).read()
        assertThat(reread.enabled).isFalse()
        assertThat(reread.minIntervalMs).isEqualTo(60 * 60_000L)
    }

    @Test
    fun `an out-of-range interval is clamped on write and on read`() {
        val store = SharedPrefsEpgSettingsStore(context)

        store.write(EpgRefreshSettings(minIntervalMs = 1L)) // far below the 30-minute floor
        assertThat(store.read().minIntervalMs).isEqualTo(EpgRefreshSettings.MIN_INTERVAL_MS)

        store.write(EpgRefreshSettings(minIntervalMs = 48 * 60 * 60_000L)) // far above the 6-hour ceiling
        assertThat(store.read().minIntervalMs).isEqualTo(EpgRefreshSettings.MAX_INTERVAL_MS)
    }
}
