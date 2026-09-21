package ilab.iptv.player.core.database

import androidx.room.migration.Migration

/**
 * Migration policy (docs/02 §5.1 / §13).
 *
 * **Version 1 is the baseline**, so [ALL] is empty on purpose — there is nothing to migrate *to* yet.
 * The path for the next version is fixed now so it cannot be improvised later:
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

    /** Every migration Room may need, oldest first. Empty while [IptvDatabase.VERSION] is 1. */
    val ALL: Array<Migration> = emptyArray()
}
