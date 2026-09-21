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

/** [BundledPlaylist] over the APK assets (`core/data/src/main/assets/playlists/...`). */
class AssetBundledPlaylist(
    private val assets: AssetManager,
    val assetPath: String = DEFAULT_ASSET,
    override val sourceId: String = DEFAULT_SOURCE_ID,
) : BundledPlaylist {

    override fun read(): ByteArray = assets.open(assetPath).use { it.readBytes() }

    companion object {
        /**
         * The P1-2 synthetic baseline (670 rows / 658 channels, `.invalid` hosts). It stays the
         * fallback after P2-6: first start with no import, and the recovery path when a remembered
         * import turns out to be unreadable.
         */
        const val DEFAULT_ASSET = "playlists/p1-2-baseline.m3u"
        const val DEFAULT_SOURCE_ID = "p1-2-fixture"
    }
}
