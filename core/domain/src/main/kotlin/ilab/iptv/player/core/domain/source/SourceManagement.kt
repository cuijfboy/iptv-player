package ilab.iptv.player.core.domain.source

import ilab.iptv.player.core.model.SourceKind

/**
 * How the user was told to read a subscription URL (P2-6 正篇 item 1: "格式提示 M3U/TXT 或自动识别").
 *
 * This is a **UI hint, not the frozen `SourceKind`**: docs/02 §4.2 says `SourceKind` describes how a
 * source is *delivered*, and its `M3U`/`TXT` values are what the parser produces by looking at the
 * body (never at the extension). A third value — "I do not know, look at it" — is what the form
 * needs and `SourceKind` deliberately does not have, so the hint lives here and maps onto the frozen
 * enum with [kindFor].
 */
enum class PlaylistHint(val label: String) {
    /** Detect from the body (the parser's normal rule). */
    AUTO("自动识别"),
    M3U("M3U"),
    TXT("TXT"),
    ;

    companion object {
        fun fromName(value: String?): PlaylistHint =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AUTO
    }
}

/** How the last fetch for a row ended; null on the row means "never fetched". */
enum class SourceFetchResult { OK, FAILED }

/**
 * One source row as the management screen reads it: the frozen [ilab.iptv.player.core.model.SourceConfig]
 * fields plus the three status columns docs/02 §5.1 already keeps on `source`
 * (`last_fetch_at` / `last_result` / `entry_count`).
 *
 * Why a second type: `SourceConfig` is a **frozen §4.2 shape** with no status fields, and the screen
 * must show "最近结果（成功/失败 + 时间）" for every row. Adding fields to the frozen type would be a
 * §4.2 signature change; a read model next to it is additive. `SourceConfig` remains the type the
 * refresh pipeline consumes.
 */
data class ManagedSource(
    val id: String,
    val label: String,
    val url: String,
    val kind: SourceKind,
    val enabled: Boolean,
    val builtIn: Boolean,
    val lastFetchAtMs: Long?,
    /** Null when the row was never fetched. */
    val lastResult: SourceFetchResult?,
    /** The raw reason the last fetch failed (a `FailureClass` name), null when it succeeded. */
    val lastFailure: String?,
    val entryCount: Int?,
)

/** What the add/edit form collects (P2-6 item 1: 名称 / URL / 格式提示 / 启用开关). */
data class SourceDraft(
    val label: String,
    val url: String,
    val hint: PlaylistHint,
    val enabled: Boolean = true,
)

/**
 * The outcome of one management action. [Missing] exists so a screen acting on a stale list (the row
 * was removed in another session) gets a defined answer instead of an exception.
 */
sealed interface SourceMutation {
    data class Saved(val source: ManagedSource) : SourceMutation

    /** [message] is user-facing prose, in the same plain Chinese the rest of the UI uses. */
    data class Rejected(val message: String) : SourceMutation

    data object Missing : SourceMutation
}

/**
 * The subscription-management surface (P2-6 正篇): list, add, edit, enable/disable, remove, and
 * record the outcome of a fetch.
 *
 * Like [ilab.iptv.player.core.domain.playlist.PlaylistImportPort] (the P2-6 slice), this port is
 * **not** in docs/02 §4.3's frozen list — the frozen `SourceRepository` three-method port is
 * implemented as well, and this is its management face. It is deliberately Android-free (no `Uri`,
 * no `Context`) so the screen and the tests drive the same interface, and any widening of §4.3 goes
 * through arch + god (docs/02 §4.0 F6).
 *
 * Every write emits the registered `DB_UPSERT` event with `table=source` and an `op` field
 * (`add`/`update`/`enable`/`disable`/`remove`) — the dispatch's "用已注册事件码" rule; docs/03 §3.3
 * has no `SRC_SUB_*` code yet, and this round must not invent one.
 */
interface SourceManagementPort {

    /** All rows, ordered by id (stable). Built-in aggregates are not in the table, so they are absent. */
    suspend fun list(): List<ManagedSource>

    /** Validates [draft], assigns an id derived from the normalized URL and inserts the row. */
    suspend fun add(draft: SourceDraft): SourceMutation

    /** Validates [draft] and rewrites row [id] (label / URL / format hint / enable switch). */
    suspend fun update(id: String, draft: SourceDraft): SourceMutation

    suspend fun setEnabled(id: String, enabled: Boolean): SourceMutation

    /**
     * Deletes a row. Named `delete` and not `remove` on purpose: [ilab.iptv.player.core.domain.repository.SourceRepository]
     * already has a `remove(id)` (returning `Unit`) and one class implements both faces, so two
     * same-named methods with different return types would not compile.
     */
    suspend fun delete(id: String): SourceMutation

    /**
     * Records what one refresh fetch produced for row [id] (`last_fetch_at` / `last_result` /
     * `entry_count`). Called by the refresh pipeline's subscription provider — the only place that
     * knows the outcome. A missing row is ignored: the user may have deleted it mid-run.
     */
    suspend fun recordOutcome(id: String, atMs: Long, ok: Boolean, entryCount: Int, detail: String?)
}
