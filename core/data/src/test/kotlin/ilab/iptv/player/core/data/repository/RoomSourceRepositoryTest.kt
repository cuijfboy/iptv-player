package ilab.iptv.player.core.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.data.RoomFixtures
import ilab.iptv.player.core.data.test
import ilab.iptv.player.core.domain.source.PlaylistHint
import ilab.iptv.player.core.domain.source.SourceDraft
import ilab.iptv.player.core.domain.source.SourceFetchResult
import ilab.iptv.player.core.domain.source.SourceMutation
import ilab.iptv.player.core.domain.source.SubscriptionRules
import ilab.iptv.player.core.model.SourceKind
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P2-6 正篇 item 1 and item 6: the subscription repository over the real `source` table (Robolectric,
 * in-memory database).
 *
 * The table already exists in docs/02 §5.1, so this test is also the evidence for the card's
 * "若是新表则补迁移" branch **not** applying: nothing here needs a v2 migration because no schema
 * changed.
 */
@RunWith(AndroidJUnit4::class)
class RoomSourceRepositoryTest {

    private val database = RoomFixtures.inMemoryDatabase()
    private val logger = RoomFixtures.RecordingLogger()
    private val repository = RoomSourceRepository(database.sourceDao(), logger)

    @Test
    fun `an added subscription is stored normalized and read back`() = test {
        val mutation = repository.add(
            SourceDraft(label = "我的源", url = "HTTPS://Example.com:443/list.m3u", hint = PlaylistHint.AUTO),
        )

        val saved = (mutation as SourceMutation.Saved).source
        assertThat(saved.url).isEqualTo("https://example.com/list.m3u")
        assertThat(saved.label).isEqualTo("我的源")
        assertThat(saved.enabled).isTrue()
        assertThat(saved.lastResult).isNull()
        assertThat(repository.list().map { it.id }).containsExactly(saved.id)
        // The frozen §4.3 port reads the same row.
        assertThat(repository.all().single().url).isEqualTo("https://example.com/list.m3u")
    }

    @Test
    fun `the same url cannot be added twice`() = test {
        repository.add(SourceDraft("第一个", "https://example.com/list.m3u", PlaylistHint.AUTO))

        val second = repository.add(SourceDraft("第二个", "https://EXAMPLE.com/list.m3u", PlaylistHint.M3U))

        assertThat(second).isInstanceOf(SourceMutation.Rejected::class.java)
        assertThat((second as SourceMutation.Rejected).message).contains("已经添加过")
        assertThat(repository.list()).hasSize(1)
    }

    @Test
    fun `the enable switch round-trips and keeps the row visible`() = test {
        val id = saved(repository.add(SourceDraft("源", "https://example.com/list.m3u", PlaylistHint.AUTO)))

        val disabled = repository.setEnabled(id, false)

        assertThat((disabled as SourceMutation.Saved).source.enabled).isFalse()
        // Disabled is not hidden: the user has to be able to turn it back on.
        assertThat(repository.list().single().enabled).isFalse()
        assertThat(repository.all().single().enabled).isFalse()
    }

    @Test
    fun `editing the url moves the row to the new id and keeps the last result`() = test {
        val first = saved(repository.add(SourceDraft("源", "https://first.example/list.m3u", PlaylistHint.AUTO)))
        repository.recordOutcome(first, atMs = 1_700_000_000_000L, ok = true, entryCount = 42, detail = null)

        val moved = repository.update(
            first,
            SourceDraft(label = "源", url = "https://second.example/list.m3u", hint = PlaylistHint.TXT),
        )

        val updated = (moved as SourceMutation.Saved).source
        assertThat(updated.id).isEqualTo(SubscriptionRules.idFor("https://second.example/list.m3u"))
        assertThat(updated.id).isNotEqualTo(first)
        assertThat(updated.kind).isEqualTo(SourceKind.TXT)
        assertThat(updated.lastResult).isEqualTo(SourceFetchResult.OK)
        assertThat(updated.entryCount).isEqualTo(42)
        assertThat(repository.list().map { it.id }).containsExactly(updated.id)
    }

    @Test
    fun `editing only the label keeps the row id and its status`() = test {
        val id = saved(repository.add(SourceDraft("旧名", "https://example.com/list.m3u", PlaylistHint.AUTO)))
        repository.recordOutcome(id, atMs = 1L, ok = false, entryCount = 0, detail = "TIMEOUT")

        val renamed = repository.update(id, SourceDraft("新名", "https://example.com/list.m3u", PlaylistHint.AUTO))

        val updated = (renamed as SourceMutation.Saved).source
        assertThat(updated.id).isEqualTo(id)
        assertThat(updated.label).isEqualTo("新名")
        assertThat(updated.lastResult).isEqualTo(SourceFetchResult.FAILED)
        assertThat(updated.lastFailure).isEqualTo("TIMEOUT")
    }

    @Test
    fun `recording an outcome writes the three status columns the screen shows`() = test {
        val id = saved(repository.add(SourceDraft("源", "https://example.com/list.m3u", PlaylistHint.AUTO)))

        repository.recordOutcome(id, atMs = 1_700_000_000_000L, ok = true, entryCount = 17, detail = null)
        val ok = repository.list().single()

        assertThat(ok.lastFetchAtMs).isEqualTo(1_700_000_000_000L)
        assertThat(ok.lastResult).isEqualTo(SourceFetchResult.OK)
        assertThat(ok.entryCount).isEqualTo(17)
        assertThat(ok.lastFailure).isNull()
    }

    @Test
    fun `a delete removes the row and a stale id answers Missing`() = test {
        val id = saved(repository.add(SourceDraft("源", "https://example.com/list.m3u", PlaylistHint.AUTO)))

        assertThat(repository.delete(id)).isInstanceOf(SourceMutation.Saved::class.java)
        assertThat(repository.list()).isEmpty()
        assertThat(repository.delete(id)).isEqualTo(SourceMutation.Missing)
        assertThat(repository.setEnabled(id, true)).isEqualTo(SourceMutation.Missing)
        assertThat(repository.update(id, SourceDraft("x", "https://x.example/a.m3u", PlaylistHint.AUTO)))
            .isEqualTo(SourceMutation.Missing)
    }

    @Test
    fun `the frozen SourceRepository port can insert and remove independently of the form`() = test {
        val config = SubscriptionRules.toConfig(
            SourceDraft("直接写入", "https://direct.example/list.m3u", PlaylistHint.AUTO),
            id = "sub:direct",
            normalizedUrl = "https://direct.example/list.m3u",
        )

        repository.upsert(config)

        assertThat(repository.all().single().id).isEqualTo("sub:direct")
        repository.remove("sub:direct")
        assertThat(repository.all()).isEmpty()
    }

    @Test
    fun `every write is logged with the registered DB code`() = test {
        val id = saved(repository.add(SourceDraft("源", "https://example.com/list.m3u", PlaylistHint.AUTO)))
        repository.setEnabled(id, false)
        repository.delete(id)

        assertThat(logger.codes).contains("DB_UPSERT")
        assertThat(logger.events.mapNotNull { it.fields["op"] }).containsAtLeast("add", "disable", "remove")
        assertThat(logger.events.map { it.fields["table"] }.toSet()).containsExactly("source")
    }

    private fun saved(mutation: SourceMutation): String {
        assertThat(mutation).isInstanceOf(SourceMutation.Saved::class.java)
        return (mutation as SourceMutation.Saved).source.id
    }
}
