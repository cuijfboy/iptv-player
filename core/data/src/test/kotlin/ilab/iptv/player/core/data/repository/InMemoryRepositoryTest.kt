package ilab.iptv.player.core.data.repository

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.FailureClass
import ilab.iptv.player.core.data.Fixtures
import ilab.iptv.player.core.data.catalog.CatalogBootstrapper
import ilab.iptv.player.core.data.catalog.CatalogLoadReport
import ilab.iptv.player.core.data.catalog.ChannelCatalog
import ilab.iptv.player.core.data.store.ChannelStore
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.StreamOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Behaviour of the in-memory repositories: the order they promise, the filter they apply and the
 * mutations the browse screen will use. The catalog is loaded from the real fixture through
 * [ChannelCatalog] — only the Android asset read is faked out, so the real pipeline runs.
 */
class InMemoryRepositoryTest {

    private val store = ChannelStore()
    private val catalog = ChannelCatalog(store)
    private val bootstrapper = FakeBootstrapper(catalog, Fixtures.text(Fixtures.BASELINE_PLAYLIST))
    private val channels = InMemoryChannelRepository(store, bootstrapper)
    private val streams = InMemoryStreamRepository(store)

    private val all = ChannelFilter(group = null, favoritesOnly = false, includeHidden = false, query = null)

    @Test
    fun `observe loads the fixture once and returns the whole sorted list`() = test {
        val first = channels.observe(all).first()
        val second = channels.observe(all).first()

        assertThat(first).hasSize(658)
        assertThat(second).hasSize(658)
        // The repository asks the bootstrapper before every emission; being idempotent is the
        // bootstrapper's contract (ChannelCatalogLoader guards it with a mutex + a loaded flag).
        assertThat(bootstrapper.loads).isAtLeast(1)
        assertThat(first.first().channel.group).isEqualTo(ChannelGroup.CCTV)
        assertThat(first.map { it.channel.id }).containsNoDuplicates()
    }

    @Test
    fun `the group filter and the counts agree`() = test {
        val cctv = channels.observe(all.copy(group = ChannelGroup.CCTV)).first()
        val counts = channels.countByGroup()

        assertThat(cctv).hasSize(80)
        assertThat(counts[ChannelGroup.CCTV]).isEqualTo(80)
        assertThat(counts[ChannelGroup.LOCAL]).isEqualTo(500)
        assertThat(counts).hasSize(5)
    }

    @Test
    fun `hidden and favourite channels are filtered as documented`() = test {
        val target = channels.observe(all).first().first().channel
        channels.setHidden(target.id, true)
        channels.setFavorite(target.id, true)

        assertThat(channels.observe(all).first().map { it.channel.id }).doesNotContain(target.id)
        assertThat(channels.observe(all.copy(includeHidden = true)).first().map { it.channel.id })
            .contains(target.id)
        assertThat(channels.observe(all.copy(favoritesOnly = true, includeHidden = true)).first())
            .hasSize(1)
    }

    @Test
    fun `a query matches the normalized name and the group`() = test {
        val byName = channels.observe(all.copy(query = "CCTV1")).first()
        val byGroup = channels.observe(all.copy(query = "港澳台")).first()

        assertThat(byName.map { it.channel.name }).contains("CCTV1")
        // "CCTV-1综合" also starts with the same key prefix, which is expected for a substring match.
        assertThat(byName).isNotEmpty()
        assertThat(byGroup).hasSize(7)
    }

    @Test
    fun `a channel number set by the user is stored as given`() = test {
        val target = channels.observe(all).first().first().channel
        channels.setChannelNo(target.id, 4242)

        assertThat(channels.get(target.id)!!.channel.channelNo).isEqualTo(4242)
        channels.setChannelNo(target.id, null)
        assertThat(channels.get(target.id)!!.channel.channelNo).isNull()
    }

    @Test
    fun `reorder rewrites sort order inside the channel's own section`() = test {
        val section = channels.observe(all.copy(group = ChannelGroup.CCTV)).first().map { it.channel }
        val moved = section.last()
        channels.reorder(moved.id, 0)

        val reordered = channels.observe(all.copy(group = ChannelGroup.CCTV)).first().map { it.channel }
        // A channel number (D12) still outranks sortOrder, so the moved channel leads the
        // unnumbered part of its section, not the whole section.
        assertThat(reordered.first { it.channelNo == null }.id).isEqualTo(moved.id)
        // Every position in the section holds a distinct sortOrder in 0..79. (The rendered order is
        // not the sortOrder order: 8 CCTV channels carry a D12 channel number and sort ahead of it.)
        assertThat(reordered.map { it.sortOrder }).containsExactlyElementsIn((0 until 80).toList())
        // The other sections must not move.
        val other = channels.observe(all.copy(group = ChannelGroup.OTHER)).first()
        assertThat(other.map { it.channel.sortOrder }).containsExactly(0, 0)
    }

    @Test
    fun `epg binding is stored on the channel`() = test {
        val target = channels.observe(all).first().first().channel
        channels.setEpgBinding(target.id, "CCTV1.cn", ilab.iptv.player.core.model.EpgMatchType.TVG_ID)

        val loaded = channels.get(target.id)!!.channel
        assertThat(loaded.epgChannelId).isEqualTo("CCTV1.cn")
        assertThat(loaded.epgMatch).isEqualTo(ilab.iptv.player.core.model.EpgMatchType.TVG_ID)
    }

    @Test
    fun `unknown channel ids are ignored instead of throwing`() = test {
        channels.setFavorite(999_999, true)
        channels.reorder(999_999, 0)

        assertThat(channels.get(999_999)).isNull()
    }

    @Test
    fun `stream candidates follow the docs selection key and skip duplicates on upsert`() = test {
        // A channel with exactly one stream, so the assertions below count only what the test adds.
        val channelId = channels.observe(all).first().first { it.channel.streamCount == 1 }.channel.id
        val original = streams.candidates(channelId)
        assertThat(original).hasSize(1)

        streams.recordOutcome(original.first().id, StreamOutcome(original.first().id, ok = false, atMs = 5))
        val afterFailure = streams.candidates(channelId).first()
        assertThat(afterFailure.failCount).isEqualTo(1)
        assertThat(afterFailure.lastCheckAtMs).isEqualTo(5)

        val backup = original.first().copy(id = 99_999, url = "http://backup.invalid/x.m3u8", urlHash = "backup")
        val better = original.first().copy(id = 88_888, url = "http://better.invalid/x.m3u8", urlHash = "better", score = 10)
        streams.upsertAll(listOf(backup, better))
        // Score wins over insertion order (docs/02 §4.3), and re-upserting the same url_hash replaces
        // rather than appends.
        streams.upsertAll(listOf(better))

        val candidates = streams.candidates(channelId)
        assertThat(candidates).hasSize(3)
        assertThat(candidates.first().id).isEqualTo(88_888)
    }

    @Test
    fun `recordOutcome stores the failure class as the last error and health counts attempts`() = test {
        val streamId = channels.observe(all).first().first().streams.first().id
        streams.recordOutcome(
            streamId,
            StreamOutcome(streamId, ok = false, atMs = 10, failure = FailureClass.TIMEOUT),
        )
        streams.recordOutcome(streamId, StreamOutcome(streamId, ok = true, atMs = 20))

        val health = streams.health(streamId)
        assertThat(health.attempts).isEqualTo(2)
        assertThat(health.lastOkAtMs).isEqualTo(20)
    }

    @Test
    fun `markStale clears the check stamp of old streams only`() = test {
        val streamId = channels.observe(all).first().first().streams.first().id
        streams.recordOutcome(streamId, StreamOutcome(streamId, ok = true, atMs = 100))

        assertThat(streams.markStale(beforeMs = 50)).isEqualTo(0)
        assertThat(streams.markStale(beforeMs = 150)).isEqualTo(1)
        assertThat(store.streams.value.first { it.id == streamId }.lastCheckAtMs).isNull()
    }

    private class FakeBootstrapper(
        private val catalog: ChannelCatalog,
        private val text: String,
    ) : CatalogBootstrapper {
        var loads = 0
            private set

        private var cached: CatalogLoadReport? = null

        override suspend fun ensureLoaded(): CatalogLoadReport? {
            loads++
            return cached ?: catalog.load(text, sourceId = "test-fixture").also { cached = it }
        }
    }

    /**
     * `runBlocking`, typed to Unit. JUnit4 rejects a test method that returns a value, and
     * `fun test() = runBlocking { … }` inherits the lambda's last expression — writing the helper
     * this way keeps every test a plain `= test { … }` without the return-type trap.
     */
    private fun test(block: suspend () -> Unit): Unit = runBlocking { block() }
}
