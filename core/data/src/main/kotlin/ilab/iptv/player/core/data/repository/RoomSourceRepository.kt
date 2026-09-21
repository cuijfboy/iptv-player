package ilab.iptv.player.core.data.repository

import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.mapper.PersistenceMapper
import ilab.iptv.player.core.database.dao.SourceDao
import ilab.iptv.player.core.database.entity.SourceEntity
import ilab.iptv.player.core.domain.repository.SourceRepository
import ilab.iptv.player.core.domain.source.ManagedSource
import ilab.iptv.player.core.domain.source.SourceFetchResult
import ilab.iptv.player.core.domain.source.SourceDraft
import ilab.iptv.player.core.domain.source.SourceManagementPort
import ilab.iptv.player.core.domain.source.SourceMutation
import ilab.iptv.player.core.domain.source.SubscriptionRules
import ilab.iptv.player.core.domain.source.SubscriptionValidation
import ilab.iptv.player.core.model.SourceConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `source` table (docs/02 §5.1), P2-6 正篇's persistence. One class answers both faces of the
 * same data:
 *
 * - [SourceRepository] — the frozen docs/02 §4.3 three-method port the refresh pipeline reads;
 * - [SourceManagementPort] — the richer management/read model the settings screen drives (add, edit,
 *   enable/disable, remove, and record the last fetch result).
 *
 * Why one class: two separate classes over one table is two places that can disagree about what a row
 * means — the same class of drift that produced the P2-1 × TESTABLE-1 integration incident. The frozen
 * type stays frozen: [SourceConfig] carries no status fields, [ManagedSource] adds the three status
 * columns the screen shows.
 *
 * **No new table and no migration.** docs/02 §5.1 already defines `source` with exactly the columns
 * this card needs (`provider_id`/`label`/`url`/`kind`/`enabled`/`last_fetch_at`/`last_result`/
 * `entry_count`), so `IptvDatabase.VERSION` stays 1 and the "新表请走 v2 迁移" branch never applies.
 * That is deliberate: a migration would be a schema change to the *existing* semantics, which the
 * dispatch forbids.
 *
 * Every write emits the registered `DB_UPSERT` code with `table=source` and an `op` field
 * (`add`/`update`/`enable`/`disable`/`remove`), and a rejected write emits `DB_FAIL` — docs/03 §3.3
 * has no subscription-specific code yet, and inventing one is out of scope (reported to arch).
 */
@Singleton
class RoomSourceRepository @Inject constructor(
    private val dao: SourceDao,
    private val logger: Logger,
) : SourceRepository, SourceManagementPort {

    override suspend fun all(): List<SourceConfig> = dao.all().map(PersistenceMapper::toDomain)

    override suspend fun upsert(cfg: SourceConfig) {
        val existing = dao.get(cfg.id)
        val entity = PersistenceMapper.toEntity(cfg).let { fresh ->
            // `REPLACE` would drop the status columns this card just added; keep them on rewrite.
            if (existing == null) fresh else fresh.copy(
                lastFetchAt = existing.lastFetchAt,
                lastResult = existing.lastResult,
                entryCount = existing.entryCount,
            )
        }
        dao.upsert(entity)
    }

    override suspend fun remove(id: String) {
        dao.remove(id)
    }

    // --- management face -------------------------------------------------------------------------

    override suspend fun list(): List<ManagedSource> = dao.all().map(::toManaged)

    override suspend fun add(draft: SourceDraft): SourceMutation {
        val current = list()
        val validation = SubscriptionRules.validate(draft, others = current)
        val accepted = validation as? SubscriptionValidation.Accepted ?: return reject(validation, "add")
        val id = SubscriptionRules.idFor(accepted.normalizedUrl)
        // Same URL, same id: an "add" of something already present is an edit of that row, so the
        // label/hint the user just typed wins instead of silently doing nothing.
        val config = SubscriptionRules.toConfig(draft, id, accepted.normalizedUrl)
        return save(config, op = if (current.any { it.id == id }) "update" else "add")
    }

    override suspend fun update(id: String, draft: SourceDraft): SourceMutation {
        val existing = dao.get(id) ?: return SourceMutation.Missing
        val current = list()
        val validation = SubscriptionRules.validate(draft, others = current, editingId = id)
        val accepted = validation as? SubscriptionValidation.Accepted ?: return reject(validation, "update")

        // Editing the URL moves the row to the id derived from the new URL (the id *is* the URL's
        // identity), so the old row is removed and a fresh one written in one pass.
        val newId = SubscriptionRules.idFor(accepted.normalizedUrl)
        val config = SubscriptionRules.toConfig(draft, newId, accepted.normalizedUrl)
        val status = PersistenceMapper.toEntity(config).copy(
            lastFetchAt = existing.lastFetchAt,
            lastResult = existing.lastResult,
            entryCount = existing.entryCount,
        )
        return try {
            if (newId != id) dao.remove(id)
            dao.upsert(status)
            log("update", status)
            SourceMutation.Saved(toManaged(status))
        } catch (e: Exception) {
            SourceMutation.Rejected(storageFailure("update", e))
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): SourceMutation {
        val existing = dao.get(id) ?: return SourceMutation.Missing
        val updated = existing.copy(enabled = if (enabled) 1 else 0)
        return try {
            dao.upsert(updated)
            log(if (enabled) "enable" else "disable", updated)
            SourceMutation.Saved(toManaged(updated))
        } catch (e: Exception) {
            SourceMutation.Rejected(storageFailure(if (enabled) "enable" else "disable", e))
        }
    }

    override suspend fun delete(id: String): SourceMutation {
        val existing = dao.get(id) ?: return SourceMutation.Missing
        return try {
            dao.remove(id)
            logger.i(
                LogCategory.DB,
                EventCodes.DB_UPSERT,
                "source deleted",
                mapOf("table" to "source", "op" to "remove", "id" to id),
            )
            SourceMutation.Saved(toManaged(existing))
        } catch (e: Exception) {
            SourceMutation.Rejected(storageFailure("remove", e))
        }
    }

    override suspend fun recordOutcome(id: String, atMs: Long, ok: Boolean, entryCount: Int, detail: String?) {
        val existing = dao.get(id) ?: return
        dao.upsert(PersistenceMapper.sourceStatus(existing, atMs, ok, entryCount, detail))
        logger.d(
            LogCategory.DB,
            EventCodes.DB_UPSERT,
            "source fetch recorded",
            mapOf(
                "table" to "source",
                "op" to "result",
                "id" to id,
                "ok" to ok,
                "entries" to entryCount,
                "at" to atMs,
            ),
        )
    }

    // --- internals -------------------------------------------------------------------------------

    private suspend fun save(config: SourceConfig, op: String): SourceMutation {
        val existing = dao.get(config.id)
        val entity = PersistenceMapper.toEntity(config).copy(
            lastFetchAt = existing?.lastFetchAt,
            lastResult = existing?.lastResult,
            entryCount = existing?.entryCount,
        )
        return try {
            dao.upsert(entity)
            log(op, entity)
            SourceMutation.Saved(toManaged(entity))
        } catch (e: Exception) {
            SourceMutation.Rejected(storageFailure(op, e))
        }
    }

    private fun reject(validation: SubscriptionValidation, op: String): SourceMutation {
        val message = (validation as? SubscriptionValidation.Rejected)?.message ?: "订阅信息不完整"
        logger.w(
            LogCategory.SOURCE,
            EventCodes.DB_FAIL,
            "source rejected",
            mapOf("table" to "source", "op" to op, "reason" to message),
        )
        return SourceMutation.Rejected(message)
    }

    private fun storageFailure(op: String, error: Throwable): String {
        logger.e(
            LogCategory.DB,
            EventCodes.DB_FAIL,
            "source write failed",
            mapOf("table" to "source", "op" to op),
            error,
        )
        return "保存失败（存储或权限问题），请稍后再试"
    }

    private fun log(op: String, entity: SourceEntity) {
        logger.i(
            LogCategory.DB,
            EventCodes.DB_UPSERT,
            "source saved",
            mapOf(
                "table" to "source",
                "op" to op,
                "id" to entity.id,
                "label" to entity.label,
                "kind" to entity.kind,
                "enabled" to (entity.enabled != 0),
            ),
        )
    }

    private fun toManaged(entity: SourceEntity): ManagedSource = ManagedSource(
        id = entity.id,
        label = entity.label ?: entity.id,
        url = entity.url.orEmpty(),
        kind = PersistenceMapper.toDomain(entity).kind,
        enabled = entity.enabled != 0,
        builtIn = false,
        lastFetchAtMs = entity.lastFetchAt,
        lastResult = when (entity.lastResult) {
            null -> null
            PersistenceMapper.SOURCE_RESULT_OK -> SourceFetchResult.OK
            else -> SourceFetchResult.FAILED
        },
        lastFailure = entity.lastResult?.takeIf { it != PersistenceMapper.SOURCE_RESULT_OK },
        entryCount = entity.entryCount,
    )
}
