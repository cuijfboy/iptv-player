package ilab.iptv.player.core.player

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The persisted overscan step (docs/04 P3-3: "画面缩放微调（±几档）持久化").
 *
 * A one-value interface rather than a `SharedPreferences` call inside the screen: the player screen
 * must not know where the value lives, and the persistence claim of P3-3 is only testable if the
 * storage can be swapped for a fake. The production implementation is [SharedPrefsOverscanStore].
 *
 * Scope note: this is the player screen's own display preference, so it lives in `:core:player`
 * next to the display pipeline, not in `:core:domain` (which stays policy-only) and not in
 * `:core:data` (`:feature:player` cannot see that module, docs/02 §3.2 rule 2).
 */
interface OverscanSettings {

    /** Current ladder index; always a valid index of [OverscanPolicy.PERCENTS]. */
    var levelIndex: Int
}

/**
 * `SharedPreferences`-backed store: one `Int`, read once at screen start and written on every change.
 *
 * `apply()` (not `commit()`) is deliberate: a step change happens on a key press and must not block
 * the UI thread on a disk write; the value is a display preference, so losing it in a process kill
 * one millisecond later is harmless. A corrupt/foreign stored value is clamped on read, so a
 * hand-edited preference file can never put the screen into an out-of-range scale.
 */
@Singleton
class SharedPrefsOverscanStore @Inject constructor(
    @ApplicationContext context: Context,
) : OverscanSettings {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override var levelIndex: Int
        get() = OverscanPolicy.clamp(prefs.getInt(KEY_LEVEL, OverscanPolicy.DEFAULT_INDEX))
        set(value) = prefs.edit().putInt(KEY_LEVEL, OverscanPolicy.clamp(value)).apply()

    private companion object {
        const val FILE_NAME = "player-display"
        const val KEY_LEVEL = "overscan_level_index"
    }
}

/**
 * Binds the store to the interface `:feature:player` injects. Kept in its own module object so
 * [PlayerModule] stays about the engine/session wiring.
 */
@Module
@InstallIn(SingletonComponent::class)
object DisplaySettingsModule {

    @Provides
    @Singleton
    fun provideOverscanSettings(store: SharedPrefsOverscanStore): OverscanSettings = store
}
