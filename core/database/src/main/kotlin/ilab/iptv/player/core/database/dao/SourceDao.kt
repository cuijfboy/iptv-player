package ilab.iptv.player.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ilab.iptv.player.core.database.entity.EpgSourceEntity
import ilab.iptv.player.core.database.entity.SourceEntity

/**
 * `source` — docs/02 §5.1. The row id is the source's own key, so writes are keyed upserts
 * (`REPLACE`) and there is nothing auto-generated to preserve.
 */
@Dao
interface SourceDao {

    @Query("SELECT * FROM source ORDER BY id ASC")
    suspend fun all(): List<SourceEntity>

    @Query("SELECT * FROM source WHERE enabled = 1 ORDER BY id ASC")
    suspend fun enabled(): List<SourceEntity>

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun get(id: String): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<SourceEntity>)

    @Query("DELETE FROM source WHERE id = :id")
    suspend fun remove(id: String): Int

    @Query("SELECT COUNT(*) FROM source")
    suspend fun count(): Int
}

/** `epg_source` — docs/02 §5.1 (same keyed-upsert shape as [SourceDao]). */
@Dao
interface EpgSourceDao {

    @Query("SELECT * FROM epg_source ORDER BY id ASC")
    suspend fun all(): List<EpgSourceEntity>

    @Query("SELECT * FROM epg_source WHERE enabled = 1 ORDER BY id ASC")
    suspend fun enabled(): List<EpgSourceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: EpgSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<EpgSourceEntity>)

    @Query("DELETE FROM epg_source WHERE id = :id")
    suspend fun remove(id: String): Int
}
