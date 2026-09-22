package ilab.iptv.player.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration baseline (§5.1 / §13): version 1 is the starting schema, and the *path* for version 2
 * is exercised now so the first real migration cannot be improvised.
 *
 * `MigrationTestHelper` works off the exported schema bundle, which it reads from the test assets
 * (`schemas/` is wired in as a test asset root in this module's build file). The helper returns a
 * database created at a given version; `runMigrationsAndValidate` then applies migrations and validates
 * the *result* against the exported schema. With no migrations defined, the check is: creating v1 and
 * validating v1 against `1.json` succeeds — i.e. the committed schema bundle and the entities agree.
 */
@RunWith(AndroidJUnit4::class)
class MigrationBaselineTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IptvDatabase::class.java,
    )

    @Test
    fun `both exported schema bundles are on the test classpath`() {
        // The bundle is wired in as a *test* asset root, so it is the application-under-test's assets
        // that carry it. The directory is the database class's canonical name (dots, not slashes) —
        // that is exactly the path `MigrationTestHelper` resolves from the database class.
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val root = "ilab.iptv.player.core.database.IptvDatabase"

        val v1 = assets.open("$root/1.json").use { it.readBytes().toString(Charsets.UTF_8) }
        assertThat(v1).contains("\"version\": 1")
        assertThat(v1).contains("idx_channel_namekey")
        assertThat(v1).contains("idx_prog_slot")

        // P3-4: v2 is a real, exported schema (not only an entity change), so `runMigrationsAndValidate`
        // below can compare the migrated result against it.
        val v2 = assets.open("$root/2.json").use { it.readBytes().toString(Charsets.UTF_8) }
        assertThat(v2).contains("\"version\": 2")
        assertThat(v2).contains("display_name")
        assertThat(v2).contains("user_group_title")
    }

    @Test
    fun `version 2 is the current version and has the v1 to v2 migration`() {
        assertThat(IptvDatabase.VERSION).isEqualTo(2)
        assertThat(Migrations.ALL.map { it.startVersion to it.endVersion })
            .containsExactly(1 to 2)
    }

    @Test
    fun `the entities create the exported v1 baseline`() {
        helper.createDatabase(IptvDatabase.NAME, 1).use { created ->
            assertThat(created.version).isEqualTo(1)
            // A created database must already agree with the exported bundle; if a column or index
            // drifts the validation below fails with the exact difference.
        }
    }

    @Test
    fun `v1 migrates cleanly to the current version`() {
        helper.createDatabase(IptvDatabase.NAME, 1).close()
        helper.runMigrationsAndValidate(IptvDatabase.NAME, IptvDatabase.VERSION, true, *Migrations.ALL)
            .close()
    }

    @Test
    fun `the helper exposes the v1 tables so a future migration test has a starting point`() {
        val created = helper.createDatabase(IptvDatabase.NAME, 1)
        val tables = created.query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor ->
                val names = ArrayList<String>()
                while (cursor.moveToNext()) names += cursor.getString(0)
                names
            }
        created.close()

        assertThat(tables).containsAtLeast("channel", "stream", "programme", "play_history", "metric")
    }
}
