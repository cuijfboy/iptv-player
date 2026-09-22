package ilab.iptv.player.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.data.catalog.AssetBundledPlaylist
import ilab.iptv.player.core.data.catalog.ChannelCatalog
import ilab.iptv.player.core.data.catalog.RoomCatalogSeeder
import ilab.iptv.player.core.data.repository.RoomChannelRepository
import ilab.iptv.player.core.data.dispatchers.TestDispatcherProvider
import ilab.iptv.player.core.data.repository.RoomStreamRepository
import ilab.iptv.player.core.data.store.RoomCatalogWriter
import ilab.iptv.player.core.database.IptvDatabase
import ilab.iptv.player.core.domain.repository.EpgChannelCatalog
import ilab.iptv.player.core.model.EpgChannelRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/**
 * The Room-side test rig for `:core:data`.
 *
 * Unlike the in-memory repository tests, these ones deliberately use the **real** `AssetManager` and
 * the real bundled fixture: the seeder's job is "cold start reads the fixture into Room", and faking
 * the asset would leave the only interesting part (asset → parse → upsert → 658 channels) untested.
 * Robolectric provides the assets, so the test still runs on the JVM.
 */
internal object RoomFixtures {

    fun context(): Context = ApplicationProvider.getApplicationContext()

    /** File-backed database; [name] lets one test close and reopen the same file (the cold start). */
    fun fileDatabase(name: String): IptvDatabase =
        Room.databaseBuilder(context(), IptvDatabase::class.java, name).build()

    fun inMemoryDatabase(): IptvDatabase =
        Room.inMemoryDatabaseBuilder(context(), IptvDatabase::class.java).build()

    /** Deletes the file of [name] and everything SQLite keeps beside it (WAL and shared memory). */
    fun deleteDatabase(name: String) {
        listOf("", "-wal", "-shm").forEach { suffix ->
            context().getDatabasePath(name + suffix).takeIf { it.exists() }?.delete()
        }
    }

    fun clock(at: Long = 1_700_000_000_000L): Clock = object : Clock {
        override fun nowMs(): Long = at
    }

    /**
     * P3-4 test double for the guide catalogue: keeps the last [replaceAll] in memory so a test can
     * assert what one EPG run published to the manual-binding picker.
     */
    class InMemoryEpgChannelCatalog : EpgChannelCatalog {
        private val state = MutableStateFlow<List<EpgChannelRef>>(emptyList())
        val written: List<EpgChannelRef> get() = state.value

        override fun observe(): Flow<List<EpgChannelRef>> = state

        override suspend fun replaceAll(refs: List<EpgChannelRef>) {
            state.value = refs
        }
    }

    /** A [Logger] that remembers what it was told, so the tests can assert the events as well. */
    class RecordingLogger : Logger {
        val events = mutableListOf<LogEvent>()
        val codes: List<String> get() = events.map { it.code }

        override fun log(event: LogEvent) {
            events += event
        }

        override fun v(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            log(LogEvent(0, 0, 0, ilab.iptv.player.core.common.LogLevel.VERBOSE, category, code, message, fields, null, "", null, ""))

        override fun d(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            log(LogEvent(0, 0, 0, ilab.iptv.player.core.common.LogLevel.DEBUG, category, code, message, fields, null, "", null, ""))

        override fun i(category: LogCategory, code: String, message: String, fields: Map<String, Any?>) =
            log(LogEvent(0, 0, 0, ilab.iptv.player.core.common.LogLevel.INFO, category, code, message, fields, null, "", null, ""))

        override fun w(
            category: LogCategory,
            code: String,
            message: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) = log(LogEvent(0, 0, 0, ilab.iptv.player.core.common.LogLevel.WARN, category, code, message, fields, error, "", null, ""))

        override fun e(
            category: LogCategory,
            code: String,
            message: String,
            fields: Map<String, Any?>,
            error: Throwable?,
        ) = log(LogEvent(0, 0, 0, ilab.iptv.player.core.common.LogLevel.ERROR, category, code, message, fields, error, "", null, ""))

        override fun flush(timeoutMs: Long) = Unit
    }

    /** The whole P2-1 stack over one database: seeder, writer and both repositories. */
    class Rig(val database: IptvDatabase, val logger: RecordingLogger = RecordingLogger()) {
        private val fixedClock = clock()
        val writer = RoomCatalogWriter(database, database.channelDao(), database.streamDao(), logger)
        // Production wiring: the catalog publishes through the CatalogSink, and that sink is the Room
        // writer — the same shape `PersistenceModule` binds, so the rig cannot pass while the app
        // would fail.
        val catalog = ChannelCatalog(writer, fixedClock)
        val seeder = RoomCatalogSeeder(
            dispatchers = TestDispatcherProvider(),
            bundled = AssetBundledPlaylist(context().assets),
            catalog = catalog,
            channelDao = database.channelDao(),
            logger = logger,
        )
        val channels = RoomChannelRepository(
            database.channelDao(),
            database.streamDao(),
            database,
            seeder,
            fixedClock,
        )
        val streams = RoomStreamRepository(database, database.streamDao(), logger)
    }
}

/** JUnit 4 needs a `void` test method; this keeps the `suspend` bodies readable. */
internal fun test(block: suspend () -> Unit): Unit = runBlocking { block() }
