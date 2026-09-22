package ilab.iptv.player.core.data.epg

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import ilab.iptv.player.core.common.DispatcherProvider
import ilab.iptv.player.core.domain.repository.EpgChannelCatalog
import ilab.iptv.player.core.model.EpgChannelRef
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * [EpgChannelCatalog] over a small text file in the app's private storage (P3-4).
 *
 * **Why a file and not a Room table.** The catalogue is *derived* data: it is exactly the `<channel>`
 * list of the guide we parsed last, so the XMLTV file is the authoritative copy and this is a cache
 * the next run overwrites. docs/02 §5.1 models durable state (`channel`, `programme`, `epg_source`);
 * adding an eighth table for a regeneratable cache would widen the schema for no data the user owns.
 * The file is written by [LoadEpgUseCase] at the end of a run and read by the manual-binding picker.
 *
 * **Format.** One `id \t display-name` pair per line, UTF-8. Tabs and newlines inside a display name
 * are folded to spaces on write, so a malformed guide cannot break the reader; a line without a tab is
 * skipped (never a crash, docs/02 §11).
 */
@Singleton
class FileEpgChannelCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) : EpgChannelCatalog {

    private val loaded = MutableStateFlow<List<EpgChannelRef>?>(null)

    override fun observe(): Flow<List<EpgChannelRef>> = flow {
        emit(ensureLoaded())
        emitAll(loaded.filterNotNull())
    }

    override suspend fun replaceAll(refs: List<EpgChannelRef>) {
        val clean = refs
            .map { EpgChannelRef(it.id.trim(), it.displayName.replace('\t', ' ').replace('\n', ' ').trim()) }
            .filter { it.id.isNotEmpty() }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.displayName.lowercase() }, { it.id }))
        withContext(dispatchers.io) {
            runCatching {
                file().writeText(clean.joinToString(separator = "\n") { "${it.id}\t${it.displayName}" })
            }
        }
        loaded.value = clean
    }

    private suspend fun ensureLoaded(): List<EpgChannelRef> {
        loaded.value?.let { return it }
        val read = withContext(dispatchers.io) { runCatching { readFile() }.getOrDefault(emptyList()) }
        loaded.value = read
        return read
    }

    private fun readFile(): List<EpgChannelRef> {
        val target = file()
        if (!target.exists()) return emptyList()
        return target.readLines()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) return@mapNotNull null
                val id = line.substring(0, tab).trim()
                val name = line.substring(tab + 1).trim()
                if (id.isEmpty()) null else EpgChannelRef(id, name.ifEmpty { id })
            }
    }

    private fun file(): File = File(context.filesDir, FILE_NAME)

    private companion object {
        const val FILE_NAME = "epg-channels.tsv"
    }
}
