package ilab.iptv.player.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.repository.EpgChannelCatalog
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.ChannelWithStreams
import ilab.iptv.player.core.model.EpgChannelRef
import ilab.iptv.player.core.model.EpgMatchType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * docs/02 §8.1's one-way data flow: the view renders [uiState] and never touches a repository.
 *
 * **P3-4 (频道管理器) adds three user-owned edits on top of P2-2:**
 *
 * - **multi-select + batch actions** ([ManageState]): hide / unhide / move-to-group / delete, all
 *   remote-driven. Every batch records **one level of undo**: the inverse of what it just did, replayed
 *   by [undoLast]. Undo lives here (a closure over the affected rows) rather than in the repository
 *   because it is a *session* affordance, not durable state — killing the process is allowed to lose
 *   the last undo, which is the same trade-off the docs make for the channel-list scroll position.
 * - **rename** [rename]: writes the user overlay, never `name_key` (see [Channel.displayName]).
 * - **manual EPG binding** [bindEpg]/[unbindEpg]: writes `MANUAL`, which `EpgMatcher` and
 *   `LoadEpgUseCase` treat as "do not touch" on the next refresh.
 *
 * The picker's candidate list is the guide's own `<channel>` list ([EpgChannelCatalog]) so the screen
 * can say honestly whether the guide has the channel at all.
 */
@HiltViewModel
class ChannelListViewModel @Inject constructor(
    private val repository: ChannelRepository,
    private val epgCatalog: EpgChannelCatalog,
) : ViewModel() {

    /**
     * The two list-wide switches P2-2 adds, kept in the view model (not the repository) because they
     * are view state: "only favourites" and "show the hidden ones too". The repository's
     * [ChannelFilter] is the frozen port that already carries them, so no new port was needed.
     */
    data class Filters(val favoritesOnly: Boolean = false, val includeHidden: Boolean = false)

    /**
     * P3-4 multi-select. [undoLabel] is non-null exactly when a one-level undo is available; the words
     * the menu shows come from here so the entry can say what it will undo.
     */
    data class ManageState(
        val active: Boolean = false,
        val selected: Set<Long> = emptySet(),
        val undoLabel: String? = null,
    )

    private val filters = MutableStateFlow(Filters())
    private val manage = MutableStateFlow(ManageState())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loaded: StateFlow<List<ChannelWithStreams>> = filters.flatMapLatest { current ->
        repository.observe(
            ChannelFilter(
                group = null,
                favoritesOnly = current.favoritesOnly,
                includeHidden = current.includeHidden,
                query = null,
            ),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /** The guide channel list the manual-binding picker searches (P3-4). */
    val guideChannels: StateFlow<List<EpgChannelRef>> = epgCatalog.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The last action's inverse, or null. One level, deliberately (see the class doc). */
    private var pendingUndo: (suspend () -> Unit)? = null

    /** The channels as last rendered, so a batch can snapshot the user columns it is about to change. */
    private val channelById: Map<Long, Channel>
        get() = loaded.value.associate { it.channel.id to it.channel }

    val uiState: StateFlow<ChannelListUiState> = combine(loaded, filters, manage) { list, current, mgmt ->
        ChannelListUiState(
            rows = ChannelListRows.build(
                list.map { it.channel },
                selected = mgmt.selected,
                manageMode = mgmt.active,
            ),
            channelCount = list.size,
            streamCount = list.sumOf { it.channel.streamCount },
            groupCount = list.map { it.channel.groupKey }.distinct().size,
            loaded = true,
            favoritesOnly = current.favoritesOnly,
            includeHidden = current.includeHidden,
            manageActive = mgmt.active,
            selectedCount = mgmt.selected.size,
            undoLabel = mgmt.undoLabel,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ChannelListUiState.Loading,
        )

    fun toggleFavoritesOnly() {
        filters.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    }

    fun toggleIncludeHidden() {
        filters.update { it.copy(includeHidden = !it.includeHidden) }
    }

    /** docs/01 F5: favourite / hidden / number / order are the four user-owned channel columns. */
    fun toggleFavorite(item: ChannelListRow.ChannelItem) {
        viewModelScope.launch { repository.setFavorite(item.channelId, !item.favorite) }
    }

    fun setHidden(item: ChannelListRow.ChannelItem, hidden: Boolean) {
        viewModelScope.launch { repository.setHidden(item.channelId, hidden) }
    }

    /**
     * docs/01 D12: a typed number, or null to clear the user's edit. The repository persists it into
     * `channel.channel_no`; the list then shows it as the channel's number.
     */
    fun setChannelNo(item: ChannelListRow.ChannelItem, number: Int?) {
        viewModelScope.launch { repository.setChannelNo(item.channelId, number) }
    }

    /**
     * Moves [item] by [delta] places inside its own `group_key` (docs/02 §5.1 / §8.1: a move never
     * crosses a section). The index is the channel's position among the *rendered* rows of its group,
     * which is exactly what the repository's `reorder` rewrites `sort_order` for.
     */
    fun move(item: ChannelListRow.ChannelItem, delta: Int) {
        val rendered = uiState.value.rows.filterIsInstance<ChannelListRow.ChannelItem>()
        val section = rendered.filter { it.groupKey == item.groupKey }
        val from = section.indexOfFirst { it.channelId == item.channelId }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, section.size - 1)
        if (to == from) return
        viewModelScope.launch { repository.reorder(item.channelId, to) }
    }

    // ---- P3-4: multi-select ----

    fun toggleManage() {
        manage.update { if (it.active) ManageState() else it.copy(active = true) }
    }

    fun toggleSelected(channelId: Long) {
        manage.update { state ->
            if (!state.active) return@update state
            val selected = state.selected.toMutableSet()
            if (!selected.add(channelId)) selected.remove(channelId)
            state.copy(selected = selected)
        }
    }

    fun clearSelection() = manage.update { it.copy(selected = emptySet()) }

    fun selectAll() {
        val visible = loaded.value.map { it.channel.id }.toSet()
        manage.update { it.copy(selected = visible) }
    }

    // ---- P3-4: batch actions (each records one level of undo) ----

    fun batchSetHidden(hidden: Boolean) {
        val ids = manage.value.selected.toList()
        if (ids.isEmpty()) return
        val before = ids.associateWith { channelById[it]?.hidden ?: false }
        viewModelScope.launch {
            ids.forEach { repository.setHidden(it, hidden) }
            setUndo(if (hidden) "隐藏 %d 台".format(ids.size) else "取消隐藏 %d 台".format(ids.size)) {
                before.forEach { (id, previous) -> repository.setHidden(id, previous) }
            }
            clearSelection()
        }
    }

    /** The picker's choice: the same user group title for every selected channel (P3-4 move-group). */
    fun batchMoveToGroup(groupTitle: String) {
        val ids = manage.value.selected.toList()
        if (ids.isEmpty()) return
        val before = ids.associateWith { channelById[it]?.userGroupTitle }
        viewModelScope.launch {
            ids.forEach { repository.setUserGroup(it, groupTitle) }
            setUndo("移动 %d 台到「%s」".format(ids.size, groupTitle)) {
                before.forEach { (id, previous) -> repository.setUserGroup(id, previous) }
            }
            clearSelection()
        }
    }

    /**
     * Hard delete of the selection (the P3-4 record argues soft vs hard). The snapshots — channel
     * **and** its streams — are taken first so [undoLast] can put the rows back with their user columns.
     */
    fun batchDelete() {
        val ids = manage.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val snapshots = ids.mapNotNull { repository.get(it) }
            repository.deleteChannels(ids)
            setUndo("删除 %d 台".format(ids.size)) { repository.restoreChannels(snapshots) }
            clearSelection()
        }
    }

    /** Undo the last batch (or rename): one level, then the affordance goes away. */
    fun undoLast() {
        val undo = pendingUndo ?: return
        viewModelScope.launch {
            pendingUndo = null
            manage.update { it.copy(undoLabel = null) }
            undo()
        }
    }

    // ---- P3-4: rename ----

    /** [displayName] blank clears the overlay and the row goes back to the source name. */
    fun rename(item: ChannelListRow.ChannelItem, displayName: String?) {
        val previous = channelById[item.channelId]?.displayName
        viewModelScope.launch {
            repository.rename(item.channelId, displayName)
            setUndo("重命名") { repository.rename(item.channelId, previous) }
        }
    }

    // ---- P3-4: manual EPG binding ----

    /**
     * Manual binding. `MANUAL` is the tier the automatic chain must not overwrite (docs/01 F5,
     * docs/02 §6.3 ⑤); the matcher returns such a channel in `preserved` instead of matching it.
     */
    fun bindEpg(channelId: Long, epgChannelId: String) {
        viewModelScope.launch { repository.setEpgBinding(channelId, epgChannelId, EpgMatchType.MANUAL) }
    }

    /**
     * Unbind. Clears both the id and the flag, deliberately `NONE` rather than `MANUAL` with a null id,
     * so the next refresh's automatic chain may adopt the channel again: the matcher only preserves a
     * `MANUAL` binding that still has an id.
     */
    fun unbindEpg(channelId: Long) {
        viewModelScope.launch { repository.setEpgBinding(channelId, null, EpgMatchType.NONE) }
    }

    private fun setUndo(label: String, inverse: suspend () -> Unit) {
        pendingUndo = inverse
        manage.update { it.copy(undoLabel = label) }
    }
}

/** What the browse screen renders (docs/02 §8.1: immutable `data class`, `StateFlow`-exposed). */
data class ChannelListUiState(
    val rows: List<ChannelListRow>,
    val channelCount: Int,
    val streamCount: Int,
    val groupCount: Int,
    val loaded: Boolean,
    /** Echoed back so the two header toggles render their pressed state (P2-2). */
    val favoritesOnly: Boolean = false,
    val includeHidden: Boolean = false,
    /** P3-4 manage mode: rows mark instead of play, and the batch actions become available. */
    val manageActive: Boolean = false,
    val selectedCount: Int = 0,
    /** Non-null when the last action can be undone; the menu shows it as "撤销：<label>". */
    val undoLabel: String? = null,
) {
    companion object {
        val Loading = ChannelListUiState(emptyList(), 0, 0, 0, loaded = false)
    }
}
