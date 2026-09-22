package ilab.iptv.player.core.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * docs/04 P3-3 item 3: 过扫描档位持久化 — proven by a real `SharedPreferences` round trip, not by
 * asserting that a setter was called.
 */
@RunWith(AndroidJUnit4::class)
class SharedPrefsOverscanStoreTest {

    private lateinit var context: Context

    @Before
    fun clearStore() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    @Test
    fun `a fresh store starts on the neutral step`() {
        assertThat(SharedPrefsOverscanStore(context).levelIndex).isEqualTo(OverscanPolicy.DEFAULT_INDEX)
    }

    @Test
    fun `the chosen step survives a new store instance`() {
        SharedPrefsOverscanStore(context).levelIndex = 5
        // A new instance is what the next app start has: same file, new object.
        assertThat(SharedPrefsOverscanStore(context).levelIndex).isEqualTo(5)
    }

    @Test
    fun `the setter clamps instead of storing an impossible step`() {
        val store = SharedPrefsOverscanStore(context)
        store.levelIndex = 99
        assertThat(store.levelIndex).isEqualTo(OverscanPolicy.PERCENTS.lastIndex)
        store.levelIndex = -3
        assertThat(store.levelIndex).isEqualTo(0)
    }

    @Test
    fun `a hand-edited preference file cannot put the screen out of range`() {
        prefs().edit().putInt("overscan_level_index", 42).commit()
        assertThat(SharedPrefsOverscanStore(context).levelIndex).isEqualTo(OverscanPolicy.PERCENTS.lastIndex)
    }

    private fun prefs() = context.getSharedPreferences("player-display", Context.MODE_PRIVATE)
}
