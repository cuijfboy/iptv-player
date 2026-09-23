package ilab.iptv.player.core.domain.refresh

/**
 * Where the EPG half of the settings lives (card EPG-SETTINGS-1): the master switch and the freshness
 * threshold, persisted so the choice survives a restart.
 *
 * A port, not a concrete store, for the same reason [EpgRefreshPort] is one: `:feature:settings` may
 * not see `:app` or `:core:data`, so the screen edits through this interface and the production
 * `SharedPreferences` implementation lives in `:app`, next to the job it configures. The value is read
 * on the cold-start path ([EpgRefreshScheduler]'s enqueue gate), so the interface is deliberately
 * *synchronous* — the same trade `FirstRunStore` and `OverscanSettings` make for their one flag: a
 * suspend read would put an `await` in front of a decision that wants to be instant.
 *
 * Reads and writes are sanitized ([EpgRefreshSettings.sanitized]): a corrupted or experimental value
 * cannot reach the policy, and an illegal one is clamped rather than rejected — the caller always gets
 * a usable settings object back.
 */
interface EpgSettingsStore {

    /** The current settings, already sanitized. Never throws: a missing/corrupt file reads as the defaults. */
    fun read(): EpgRefreshSettings

    /** Persists [settings] (sanitized first). The write must be visible to the next [read] in this process. */
    fun write(settings: EpgRefreshSettings)
}
