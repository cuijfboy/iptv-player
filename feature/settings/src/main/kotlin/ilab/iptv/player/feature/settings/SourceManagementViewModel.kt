package ilab.iptv.player.feature.settings

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ilab.iptv.player.core.domain.source.ManagedSource
import ilab.iptv.player.core.domain.source.SourceDraft
import ilab.iptv.player.core.domain.source.SourceManagementPort
import ilab.iptv.player.core.domain.source.SourceMutation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * docs/02 §8.1's one-way data flow for the source-management screen: the view renders [sources] and
 * calls the suspend actions, which return what happened so the screen can show it verbatim.
 *
 * The actions are `suspend` and **not** launched in `viewModelScope` on purpose: the screen already
 * has a coroutine scope (`lifecycleScope`), a mutation must finish before the toast is shown, and a
 * view model that owns its own dispatcher would need `Dispatchers.Main` in the unit tests. Every
 * write goes through [SourceManagementPort], so the screen never sees Room, the parser or `:core:data`
 * (docs/02 §3.2 rule 2).
 */
@HiltViewModel
class SourceManagementViewModel @Inject constructor(
    private val port: SourceManagementPort,
) : ViewModel() {

    private val _sources = MutableStateFlow<List<ManagedSource>>(emptyList())
    val sources: StateFlow<List<ManagedSource>> = _sources.asStateFlow()

    /** "Loading finished, possibly with zero rows" — so the screen can tell empty from not-yet-read. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Re-reads the table. Cheap (one `SELECT`), so it runs after every successful mutation. */
    suspend fun load() {
        _sources.value = port.list()
        _loaded.value = true
    }

    suspend fun add(draft: SourceDraft): SourceMutation = port.add(draft).also { afterWrite(it) }

    suspend fun update(id: String, draft: SourceDraft): SourceMutation = port.update(id, draft).also { afterWrite(it) }

    suspend fun setEnabled(id: String, enabled: Boolean): SourceMutation =
        port.setEnabled(id, enabled).also { afterWrite(it) }

    suspend fun delete(id: String): SourceMutation = port.delete(id).also { afterWrite(it) }

    /** The row a dialog is editing, looked up in the list already in hand (no extra query). */
    fun find(id: String): ManagedSource? = _sources.value.firstOrNull { it.id == id }

    private suspend fun afterWrite(mutation: SourceMutation) {
        if (mutation is SourceMutation.Saved) load()
    }
}
