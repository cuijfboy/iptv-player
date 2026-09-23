package ilab.iptv.player.epg

import android.content.Context
import ilab.iptv.player.core.domain.refresh.EpgRefreshSettings
import ilab.iptv.player.core.domain.refresh.EpgSettingsStore

/**
 * The EPG settings (master switch + freshness threshold) in a one-file `SharedPreferences` store
 * (card EPG-SETTINGS-1) — the same trade [ilab.iptv.player.refresh.SharedPrefsRefreshRunLedger],
 * `SharedPrefsFirstRunStore` and `SharedPrefsOverscanStore` already make for a couple of scalars: no
 * Room schema change, no DataStore round trip.
 *
 * WHY `SharedPreferences` AND NOT Room/DataStore: [read] is on the cold-start path
 * ([EpgRefreshScheduler]'s enqueue gate) and on the run path, where the value decides whether a job is
 * queued at all. `SharedPreferences` answers it with a synchronous in-memory read after the first load;
 * DataStore is suspend-only and a Room table would be a schema change for two scalars that belong to no
 * entity. The values are two scalars read by `:app`, so nothing else needs to observe them.
 *
 * BOTH WRITES AND READS ARE SANITIZED ([EpgRefreshSettings.sanitized]): an out-of-range interval is
 * clamped into the §6.3 [EpgRefreshSettings.MIN_INTERVAL_MS]..[EpgRefreshSettings.MAX_INTERVAL_MS] band
 * rather than rejected, so a corrupted or hand-edited preference can never reach the policy. `apply()`
 * is used (not `commit()`): the settings page's next [read] in the same process sees the new value
 * immediately from memory, and the disk write is a background flush — exactly the visibility the page
 * and the scheduler need.
 */
class SharedPrefsEpgSettingsStore(context: Context) : EpgSettingsStore {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(): EpgRefreshSettings = EpgRefreshSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        minIntervalMs = prefs.getLong(KEY_MIN_INTERVAL_MS, EpgRefreshSettings.DEFAULT_MIN_INTERVAL_MS),
        // Not user-editable this round; kept at its default so the empty-guide retry is unchanged.
        emptyRetryMs = EpgRefreshSettings.DEFAULT_EMPTY_RETRY_MS,
    ).sanitized()

    override fun write(settings: EpgRefreshSettings) {
        val safe = settings.sanitized()
        prefs.edit()
            .putBoolean(KEY_ENABLED, safe.enabled)
            .putLong(KEY_MIN_INTERVAL_MS, safe.minIntervalMs)
            .apply()
    }

    companion object {

        /** The file name and the two keys are read back by the Robolectric round-trip test. */
        const val FILE_NAME: String = "epg-settings"
        const val KEY_ENABLED: String = "enabled"
        const val KEY_MIN_INTERVAL_MS: String = "min-interval-ms"
    }
}
