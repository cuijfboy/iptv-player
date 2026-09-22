package ilab.iptv.player.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration policy (docs/02 §5.1 / §13).
 *
 * **Version 1 is the baseline.** Version 2 (P3-4) is the first real migration; it follows the path
 * below, which the v1 baseline wrote down so it could not be improvised:
 *
 * 1. change the entities and bump [IptvDatabase.VERSION];
 * 2. run a build: KSP writes `schemas/ilab/iptv/player/core/database/IptvDatabase/<newVersion>.json`
 *    next to `1.json` — **commit both**, they are the contract `MigrationTestHelper` validates;
 * 3. add the `Migration(old, new)` to [ALL] and to `IptvDatabase.build` (it is added wholesale);
 * 4. add a `Migration_<old>_<new>Test` next to `MigrationBaselineTest` that seeds the old schema with
 *    rows, calls `runMigrationsAndValidate(name, new, true, *Migrations.ALL)`, and asserts the data
 *    survived. Destructive fallback is never an option (see `IptvDatabase.build`).
 */
object Migrations {

    /**
     * v1 → v2 (P3-4 频道管理器): two **additive, nullable** columns on `channel`.
     *
     * - `display_name` — the user's rename overlay ([ilab.iptv.player.core.model.Channel.displayName]).
     * - `user_group_title` — the user's group-move overlay (同上, `userGroupTitle`).
     *
     * Both are `ALTER TABLE ... ADD COLUMN` with no default and no backfill: every existing row keeps
     * its behaviour (NULL = "no user overlay, show the source value"), which is exactly the semantics
     * the P3-4 record argues for. No other table changes, so no data needs copying and the migration
     * cannot fail halfway through a rewrite.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE channel ADD COLUMN display_name TEXT")
            db.execSQL("ALTER TABLE channel ADD COLUMN user_group_title TEXT")
        }
    }

    /** Every migration Room may need, oldest first. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
