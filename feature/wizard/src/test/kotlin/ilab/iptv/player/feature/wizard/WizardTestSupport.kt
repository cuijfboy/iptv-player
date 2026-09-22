package ilab.iptv.player.feature.wizard

import ilab.iptv.player.core.domain.playlist.ImportCandidate
import ilab.iptv.player.core.domain.playlist.ImportFolders
import ilab.iptv.player.core.domain.playlist.ImportReport
import ilab.iptv.player.core.domain.playlist.ImportResult
import ilab.iptv.player.core.domain.playlist.ImportedPlaylist
import ilab.iptv.player.core.domain.playlist.PlaylistImportPort
import ilab.iptv.player.core.domain.repository.ChannelRepository
import ilab.iptv.player.core.domain.source.ManagedSource
import ilab.iptv.player.core.domain.source.SourceDraft
import ilab.iptv.player.core.domain.source.SourceManagementPort
import ilab.iptv.player.core.domain.source.SourceMutation
import ilab.iptv.player.core.domain.wizard.BuiltInSourceCatalog
import ilab.iptv.player.core.domain.wizard.BuiltInSourceInfo
import ilab.iptv.player.core.domain.wizard.FirstRunStore
import ilab.iptv.player.core.domain.wizard.WizardUpdatePort
import ilab.iptv.player.core.domain.wizard.WizardState
import ilab.iptv.player.core.domain.wizard.WizardStep
import ilab.iptv.player.core.domain.wizard.WizardUpdateSnapshot
import ilab.iptv.player.core.model.Channel
import ilab.iptv.player.core.model.ChannelFilter
import ilab.iptv.player.core.model.ChannelGroup
import ilab.iptv.player.core.model.ChannelWithStreams
import ilab.iptv.player.core.model.EpgMatchType
import ilab.iptv.player.core.model.SourceKind
import ilab.iptv.player.core.model.Stream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The fakes the wizard's view-model tests drive.
 *
 * They are deliberately tiny and recording: the card's acceptance is about *decisions* (which step,
 * skipped or not, what the catalog says), so each fake answers one question and remembers what it was
 * asked, instead of imitating a repository.
 */

/**
 * A [FirstRunStore] in memory; [completed] is read back exactly as the router would read it, and
 * [saved] is the NEW-1 resume slot — constructed with a value to imitate "the user left the wizard on
 * step N and started the app again".
 */
class FakeFirstRunStore(
    var completed: Boolean = false,
    var saved: WizardState? = null,
) : FirstRunStore {

    override fun isCompleted(): Boolean = completed

    override fun markCompleted() {
        completed = true
        // The real store drops the resume slot on completion; the fake must not be more forgiving.
        saved = null
    }

    override fun savedProgress(): WizardState? = saved

    override fun saveProgress(state: WizardState) {
        if (state.step == WizardStep.FINISHED) return
        saved = state
    }
}

/** A fixed built-in catalogue, so the 选源 step's list is assertable. */
class FakeBuiltInCatalog(
    private val sources: List<BuiltInSourceInfo> = listOf(
        BuiltInSourceInfo("builtin.one", "内置源一", SourceKind.M3U),
        BuiltInSourceInfo("builtin.two", "内置源二", SourceKind.TXT),
    ),
) : BuiltInSourceCatalog {
    override fun sources(): List<BuiltInSourceInfo> = sources
}

/** The subscription port: only [list] is meaningful for the wizard. */
class FakeSourceManagementPort(var rows: List<ManagedSource> = emptyList()) : SourceManagementPort {

    override suspend fun list(): List<ManagedSource> = rows

    override suspend fun add(draft: SourceDraft): SourceMutation = SourceMutation.Missing

    override suspend fun update(id: String, draft: SourceDraft): SourceMutation = SourceMutation.Missing

    override suspend fun setEnabled(id: String, enabled: Boolean): SourceMutation = SourceMutation.Missing

    override suspend fun delete(id: String): SourceMutation = SourceMutation.Missing

    override suspend fun recordOutcome(
        id: String,
        atMs: Long,
        ok: Boolean,
        entryCount: Int,
        detail: String?,
    ) = Unit
}

/**
 * The channel catalog as a `StateFlow`, so a test can add channels mid-flight and watch the 开看 step
 * change its mind — which is exactly what happens while the 更新 step runs.
 */
class FakeChannelRepository(initial: List<ChannelWithStreams> = emptyList()) : ChannelRepository {

    val channels = MutableStateFlow(initial)

    override fun observe(filter: ChannelFilter): Flow<List<ChannelWithStreams>> = channels

    override suspend fun get(channelId: Long): ChannelWithStreams? =
        channels.value.firstOrNull { it.channel.id == channelId }

    override suspend fun setFavorite(channelId: Long, favorite: Boolean) = Unit

    override suspend fun setHidden(channelId: Long, hidden: Boolean) = Unit

    override suspend fun reorder(channelId: Long, newIndex: Int) = Unit

    override suspend fun setChannelNo(channelId: Long, channelNo: Int?) = Unit

    override suspend fun setEpgBinding(channelId: Long, epgChannelId: String?, match: EpgMatchType) = Unit

    override suspend fun rename(channelId: Long, displayName: String?) = Unit

    override suspend fun setUserGroup(channelId: Long, groupTitle: String?) = Unit

    override suspend fun deleteChannels(channelIds: List<Long>): Int = 0

    override suspend fun restoreChannels(items: List<ChannelWithStreams>): Int = 0

    override suspend fun countByGroup(): Map<ChannelGroup, Int> = emptyMap()
}

/** The import port: a fixed candidate list and a scripted answer per import. */
class FakePlaylistImportPort(
    private var candidates: List<ImportCandidate> = emptyList(),
    private var remembered: ImportedPlaylist? = null,
    var nextResult: ImportResult = ImportResult.Done(report("list.m3u", channels = 3, streams = 5)),
) : PlaylistImportPort {

    val folders = ImportFolders(dropFolder = "/tmp/playlists", storeFolder = "/tmp/imports")

    override fun folders(): ImportFolders = folders

    override suspend fun candidates(): List<ImportCandidate> = candidates

    override suspend fun import(candidate: ImportCandidate): ImportResult {
        val result = nextResult
        if (result is ImportResult.Done) remembered = imported(result.report)
        return result
    }

    override suspend fun importUri(uri: String): ImportResult = import(
        ImportCandidate(path = uri, name = "picked.m3u", sizeBytes = 1, modifiedAtMs = 0),
    )

    override suspend fun lastImported(): ImportedPlaylist? = remembered

    companion object {

        fun report(name: String, channels: Int, streams: Int): ImportReport = ImportReport(
            name = name,
            sourceId = "local:$name",
            formatLabel = "m3u",
            rawEntries = streams,
            skipped = 0,
            channels = channels,
            streams = streams,
            elapsedMs = 12,
            copiedPath = "/tmp/imports/$name",
        )

        private fun imported(report: ImportReport): ImportedPlaylist = ImportedPlaylist(
            name = report.name,
            sourceId = report.sourceId,
            copiedPath = report.copiedPath,
            sizeBytes = 10,
            importedAtMs = 0,
        )
    }
}

/** The 更新 port: a `StateFlow` a test pushes snapshots into, plus the two calls it records. */
class FakeWizardUpdatePort : WizardUpdatePort {

    val snapshots = MutableStateFlow(WizardUpdateSnapshot())

    var startCount: Int = 0
        private set
    var cancelCount: Int = 0
        private set

    override fun observe(): Flow<WizardUpdateSnapshot> = snapshots

    override suspend fun start() {
        startCount++
    }

    override suspend fun cancel() {
        cancelCount++
    }
}

/** One channel with [streamCount] streams; the wizard only reads the count. */
fun channelWithStreams(id: Long, name: String, streamCount: Int): ChannelWithStreams = ChannelWithStreams(
    channel = Channel(
        id = id,
        name = name,
        nameKey = name,
        tvgId = null,
        group = ChannelGroup.CCTV,
        groupKey = ChannelGroup.CCTV.key,
        groupTitle = "央视",
        logoUrl = null,
        channelNo = null,
        favorite = false,
        hidden = false,
        sortOrder = id.toInt(),
        epgChannelId = null,
        epgMatch = EpgMatchType.NONE,
        streamCount = streamCount,
    ),
    streams = List(streamCount) { stream(it) },
)

private fun stream(index: Int): Stream = Stream(
    id = index.toLong(),
    channelId = 0,
    url = "https://example.invalid/$index.m3u8",
    userAgent = null,
    referrer = null,
    sourceId = "test",
    quality = null,
    videoCodec = null,
    audioCodec = null,
    width = 0,
    height = 0,
    score = 0,
    priority = index,
    lastOkAtMs = null,
    lastCheckAtMs = null,
    failCount = 0,
    lastError = null,
    disabled = false,
)
