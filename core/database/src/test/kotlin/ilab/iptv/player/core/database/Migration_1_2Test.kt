package ilab.iptv.player.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The v1 → v2 migration (P3-4 频道管理器). Two additive nullable columns on `channel`; the contract the
 * test pins is **no data loss and no behaviour change**: every stored row keeps its columns, and both
 * overlays start out `NULL` ("no user edit, show the source value").
 */
@RunWith(AndroidJUnit4::class)
class Migration_1_2Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IptvDatabase::class.java,
    )

    @Test
    fun `channel rows survive v1 to v2 and gain two null overlays`() {
        helper.createDatabase(IptvDatabase.NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO channel
                    (name, name_key, tvg_id, group_key, group_title, logo, channel_no,
                     favorite, hidden, sort_order, epg_channel_id, epg_match, created_at, updated_at)
                VALUES
                    ('CCTV1', 'cctv1', NULL, 'cctv', '央视', NULL, 1, 1, 0, 0, NULL, 'NONE', 10, 20)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(IptvDatabase.NAME, 2, true, *Migrations.ALL).use { db ->
            db.query(
                "SELECT name, favorite, channel_no, display_name, user_group_title FROM channel",
            ).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("CCTV1")
                assertThat(cursor.getInt(1)).isEqualTo(1)
                assertThat(cursor.getInt(2)).isEqualTo(1)
                assertThat(cursor.isNull(3)).isTrue()
                assertThat(cursor.isNull(4)).isTrue()
                assertThat(cursor.moveToNext()).isFalse()
            }
        }
    }
}
