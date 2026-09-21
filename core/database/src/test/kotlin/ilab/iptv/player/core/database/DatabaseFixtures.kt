package ilab.iptv.player.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ilab.iptv.player.core.database.entity.ChannelEntity
import ilab.iptv.player.core.database.entity.MetricEntity
import ilab.iptv.player.core.database.entity.PlayHistoryEntity
import ilab.iptv.player.core.database.entity.ProgrammeEntity
import ilab.iptv.player.core.database.entity.StreamEntity
import kotlinx.coroutines.runBlocking

/**
 * Test scaffolding for the Room layer. `Room.inMemoryDatabaseBuilder` keeps every DAO test on the JVM
 * (Robolectric), so `./gradlew check` — and therefore CI — runs the schema, index, upsert and
 * migration tests without a device (docs/02 §13; the card explicitly asks for this).
 *
 * `allowMainThreadQueries()` is a *test-only* convenience: Robolectric runs the test body on the main
 * thread, and production explicitly does not set it (see `IptvDatabase.build`).
 */
internal object DatabaseFixtures {

    fun inMemory(): IptvDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            IptvDatabase::class.java,
        ).allowMainThreadQueries().build()

    fun channel(
        name: String,
        groupKey: String = "cctv",
        nameKey: String = name.lowercase(),
        groupTitle: String? = groupKey,
        channelNo: Int? = null,
        favorite: Boolean = false,
        hidden: Boolean = false,
        sortOrder: Int = 0,
        epgChannelId: String? = null,
        epgMatch: String = "NONE",
        createdAt: Long = 1_000L,
        updatedAt: Long = 1_000L,
    ): ChannelEntity = ChannelEntity(
        id = 0,
        name = name,
        nameKey = nameKey,
        tvgId = null,
        groupKey = groupKey,
        groupTitle = groupTitle,
        logo = null,
        channelNo = channelNo,
        favorite = favorite,
        hidden = hidden,
        sortOrder = sortOrder,
        epgChannelId = epgChannelId,
        epgMatch = epgMatch,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    fun stream(
        channelId: Long,
        url: String = "http://example.invalid/$channelId.m3u8",
        urlHash: String = "hash-$url",
        score: Int = 0,
        priority: Int = 0,
        lastOkAt: Long? = null,
        lastCheckAt: Long? = null,
        failCount: Int = 0,
        disabled: Boolean = false,
    ): StreamEntity = StreamEntity(
        id = 0,
        channelId = channelId,
        url = url,
        urlHash = urlHash,
        userAgent = null,
        referrer = null,
        sourceId = "test",
        quality = null,
        vcodec = null,
        acodec = null,
        width = 0,
        height = 0,
        score = score,
        priority = priority,
        lastOkAt = lastOkAt,
        lastCheckAt = lastCheckAt,
        failCount = failCount,
        lastError = null,
        disabled = disabled,
    )

    fun programme(
        epgChannelId: String = "cctv1",
        startMs: Long,
        stopMs: Long = startMs + 1_800_000,
        title: String = "节目 $startMs",
    ): ProgrammeEntity = ProgrammeEntity(
        id = 0,
        epgChannelId = epgChannelId,
        startMs = startMs,
        stopMs = stopMs,
        title = title,
        desc = null,
        category = null,
    )

    fun history(streamId: Long?, channelId: Long?, result: String, atMs: Long): PlayHistoryEntity =
        PlayHistoryEntity(
            id = 0,
            channelId = channelId,
            streamId = streamId,
            startedAt = atMs,
            startCostMs = 500,
            result = result,
            failoverCount = 0,
        )

    fun metric(name: String, value: Double, at: Long): MetricEntity =
        MetricEntity(id = 0, name = name, value = value, channelId = null, streamId = null, at = at)
}

/** JUnit 4 wants a `void` test method; a `suspend` body would return the wrong type. */
internal fun test(block: suspend () -> Unit): Unit = runBlocking { block() }
