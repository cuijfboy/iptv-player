package ilab.iptv.player.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3-4's two user-owned overlays at the DAO floor.
 *
 * The load-bearing assertion is the **refresh** one: `upsertAll` must treat `display_name` /
 * `user_group_title` like `favorite` / `hidden` — a playlist refresh updates the source columns and
 * leaves the user's edits alone (docs/01 F5, the rule P2-2 pinned for the four original columns).
 */
@RunWith(AndroidJUnit4::class)
class ChannelUserOverlayDaoTest {

    private lateinit var database: IptvDatabase
    private val dao get() = database.channelDao()

    @Before
    fun setUp() {
        database = DatabaseFixtures.inMemory()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a refresh keeps both overlays and still updates the source columns`() = test {
        val id = dao.insert(
            DatabaseFixtures.channel(
                name = "凤凰卫视中文台",
                nameKey = "凤凰卫视中文台",
                groupKey = "hmt",
                groupTitle = "港澳台",
                displayName = "凤凰中文",
                userGroupTitle = "我的收藏台",
            ),
        )

        // The same channel comes back from the playlist: same identity, new source spelling.
        val merged = dao.upsertAll(
            listOf(
                DatabaseFixtures.channel(
                    name = "凤凰卫视中文台 高清",
                    nameKey = "凤凰卫视中文台",
                    groupKey = "hmt",
                    groupTitle = "港澳台",
                ),
            ),
        )

        assertThat(merged).containsExactly(id)
        val row = dao.getWithStreams(id)!!.channel
        assertThat(row.name).isEqualTo("凤凰卫视中文台 高清")
        assertThat(row.displayName).isEqualTo("凤凰中文")
        assertThat(row.userGroupTitle).isEqualTo("我的收藏台")
    }

    @Test
    fun `the overlay setters round trip and can be cleared`() = test {
        val id = dao.insert(DatabaseFixtures.channel(name = "CCTV1", nameKey = "cctv1", groupKey = "cctv"))

        dao.setDisplayName(id, "中央一套", updatedAt = 5L)
        dao.setUserGroupTitle(id, "我的分组", updatedAt = 6L)
        var row = dao.getWithStreams(id)!!.channel
        assertThat(row.displayName).isEqualTo("中央一套")
        assertThat(row.userGroupTitle).isEqualTo("我的分组")

        dao.setDisplayName(id, null, updatedAt = 7L)
        dao.setUserGroupTitle(id, null, updatedAt = 8L)
        row = dao.getWithStreams(id)!!.channel
        assertThat(row.displayName).isNull()
        assertThat(row.userGroupTitle).isNull()
    }

    @Test
    fun `deleting a channel takes its streams with it`() = test {
        val id = dao.insert(DatabaseFixtures.channel(name = "CCTV1", nameKey = "cctv1", groupKey = "cctv"))
        database.streamDao().insert(DatabaseFixtures.stream(channelId = id))
        assertThat(database.streamDao().countForChannel(id)).isEqualTo(1)

        assertThat(dao.deleteByIds(listOf(id))).isEqualTo(1)

        assertThat(dao.findIdOrNull(id)).isNull()
        assertThat(database.streamDao().countForChannel(id)).isEqualTo(0)
    }
}
