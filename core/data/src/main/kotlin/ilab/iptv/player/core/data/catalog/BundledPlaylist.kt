package ilab.iptv.player.core.data.catalog

import android.content.res.AssetManager

/**
 * The playlist the app ships with, as an injectable seam.
 *
 * It exists so [ChannelCatalogLoader] can be unit-tested at all: `AssetManager` cannot be faked off
 * a device, and the interesting branch — "restore the remembered import, else read the fixture,
 * else fall back if the remembered file is broken" — is exactly the kind of thing that must be
 * pinned by a test rather than by a device run.
 */
interface BundledPlaylist {

    /** `channel.source_id` for every row the bundled file produces. */
    val sourceId: String

    fun read(): ByteArray
}

/**
 * [BundledPlaylist] over the APK assets.
 *
 * Two different files can be read through this seam:
 * - the **default** (`playlists/p1-2-baseline.m3u`): the P1-2 synthetic fixture, still the test
 *   fixture the Room rig parses (`.invalid` hosts, no third-party URLs);
 * - the **snapshot** (`snapshot/channels.m3u`, [SNAPSHOT_ASSET]): the SNAPSHOT-1 list of channels
 *   that were measured playable *on the TV*, which the production graph binds
 *   ([ilab.iptv.player.core.data.di.DataModule]).
 */
class AssetBundledPlaylist(
    private val assets: AssetManager,
    val assetPath: String = DEFAULT_ASSET,
    override val sourceId: String = DEFAULT_SOURCE_ID,
) : BundledPlaylist {

    override fun read(): ByteArray = assets.open(assetPath).use { it.readBytes() }

    companion object {
        /**
         * The P1-2 synthetic baseline (670 rows / 658 channels, `.invalid` hosts). It stays the
         * fallback for the memory loader and the fixture the `:core:data` tests parse.
         */
        const val DEFAULT_ASSET = "playlists/p1-2-baseline.m3u"
        const val DEFAULT_SOURCE_ID = "p1-2-fixture"

        /**
         * SNAPSHOT-1: the list the app *ships* so a fresh install has something to watch without a
         * network refresh and without the user importing anything. Every channel in it was measured
         * playable **on the TV itself** (see `app/src/main/assets/snapshot/PROVENANCE.md`), so it is
         * the one bundled file that carries real third-party stream URLs.
         *
         * It lives in `:app`'s assets — the snapshot is shipped product data, and `:core:data` only
         * needs the `AssetManager` seam to read it. A build where the asset is missing degrades to
         * "no catalog yet" (the seeder logs `DB_FAIL` and swallows it), it does not crash.
         */
        const val SNAPSHOT_ASSET = "snapshot/channels.m3u"
        const val SNAPSHOT_SOURCE_ID = "snapshot-1"
    }
}
