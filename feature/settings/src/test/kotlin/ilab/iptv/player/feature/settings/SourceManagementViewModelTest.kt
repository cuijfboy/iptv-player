package ilab.iptv.player.feature.settings

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.domain.source.ManagedSource
import ilab.iptv.player.core.domain.source.PlaylistHint
import ilab.iptv.player.core.domain.source.SourceDraft
import ilab.iptv.player.core.domain.source.SourceManagementPort
import ilab.iptv.player.core.domain.source.SourceMutation
import ilab.iptv.player.core.model.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The screen's only logic worth testing off-device: the view model never touches a repository itself,
 * refreshes the list after a successful write, and passes the port's answer through unchanged so the
 * screen can show it.
 *
 * Written without `Dispatchers.Main` on purpose — the actions are `suspend` and the caller owns the
 * scope, so the view model's behaviour is testable with plain `runBlocking`.
 */
class SourceManagementViewModelTest {

    private val port = FakePort()
    private val viewModel = SourceManagementViewModel(port)

    @Test
    fun `load reads the port and flips loaded`() = test {
        port.rows += source("sub:a", "源 A", enabled = true)

        viewModel.load()

        assertThat(viewModel.sources.value.map { it.label }).containsExactly("源 A")
        assertThat(viewModel.loaded.value).isTrue()
    }

    @Test
    fun `a saved mutation refreshes the list`() = test {
        viewModel.load()
        assertThat(viewModel.sources.value).isEmpty()
        // The port answers with a row and stores it; the view model must read the table back.
        port.onAdd = { draft ->
            port.rows += source("sub:new", draft.label, enabled = draft.enabled)
            SourceMutation.Saved(port.rows.last())
        }

        val mutation = viewModel.add(SourceDraft("新源", "https://example.com/list.m3u", PlaylistHint.AUTO))

        assertThat(mutation).isInstanceOf(SourceMutation.Saved::class.java)
        assertThat(viewModel.sources.value.map { it.label }).containsExactly("新源")
    }

    @Test
    fun `a rejected mutation leaves the list as it was`() = test {
        port.rows += source("sub:a", "源 A", enabled = true)
        viewModel.load()
        port.onAdd = { SourceMutation.Rejected("这个地址已经添加过：源 A") }

        val mutation = viewModel.add(SourceDraft("重复", "https://example.com/list.m3u", PlaylistHint.AUTO))

        assertThat(mutation).isEqualTo(SourceMutation.Rejected("这个地址已经添加过：源 A"))
        // A rejected write is not followed by a re-read: there is nothing new to show.
        assertThat(port.listCalls).isEqualTo(1)
        assertThat(viewModel.sources.value.map { it.label }).containsExactly("源 A")
    }

    @Test
    fun `the enable switch and delete go through the port`() = test {
        port.rows += source("sub:a", "源 A", enabled = true)
        viewModel.load()
        port.onSetEnabled = { id, enabled ->
            port.rows = port.rows.map { if (it.id == id) it.copy(enabled = enabled) else it }.toMutableList()
            SourceMutation.Saved(port.rows.single())
        }
        port.onDelete = { id ->
            val gone = port.rows.single { it.id == id }
            port.rows = port.rows.filterNot { it.id == id }.toMutableList()
            SourceMutation.Saved(gone)
        }

        viewModel.setEnabled("sub:a", false)
        assertThat(viewModel.sources.value.single().enabled).isFalse()

        viewModel.delete("sub:a")
        assertThat(viewModel.sources.value).isEmpty()
    }

    @Test
    fun `a stale id answers Missing and does not touch the list`() = test {
        viewModel.load()

        val mutation = viewModel.setEnabled("sub:gone", true)

        assertThat(mutation).isEqualTo(SourceMutation.Missing)
        assertThat(viewModel.sources.value).isEmpty()
    }

    @Test
    fun `find looks a row up in the list already in hand`() = test {
        port.rows += source("sub:a", "源 A", enabled = true)
        viewModel.load()

        assertThat(viewModel.find("sub:a")?.label).isEqualTo("源 A")
        assertThat(viewModel.find("sub:other")).isNull()
    }

    private fun test(block: suspend () -> Unit): Unit = runBlocking { block() }

    private fun source(id: String, label: String, enabled: Boolean) = ManagedSource(
        id = id,
        label = label,
        url = "https://example.com/list.m3u",
        kind = SourceKind.M3U,
        enabled = enabled,
        builtIn = false,
        lastFetchAtMs = null,
        lastResult = null,
        lastFailure = null,
        entryCount = null,
    )

    private class FakePort : SourceManagementPort {
        var rows: MutableList<ManagedSource> = mutableListOf()
        var listCalls = 0
        var onAdd: (SourceDraft) -> SourceMutation = { SourceMutation.Rejected("not stubbed") }
        var onSetEnabled: (String, Boolean) -> SourceMutation = { _, _ -> SourceMutation.Missing }
        var onDelete: (String) -> SourceMutation = { SourceMutation.Missing }

        override suspend fun list(): List<ManagedSource> {
            listCalls++
            return rows.toList()
        }

        override suspend fun add(draft: SourceDraft): SourceMutation = onAdd(draft)

        override suspend fun update(id: String, draft: SourceDraft): SourceMutation =
            SourceMutation.Missing

        override suspend fun setEnabled(id: String, enabled: Boolean): SourceMutation = onSetEnabled(id, enabled)

        override suspend fun delete(id: String): SourceMutation = onDelete(id)

        override suspend fun recordOutcome(id: String, atMs: Long, ok: Boolean, entryCount: Int, detail: String?) =
            Unit
    }
}
