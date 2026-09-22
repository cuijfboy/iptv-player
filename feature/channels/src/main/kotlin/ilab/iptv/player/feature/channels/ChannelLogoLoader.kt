package ilab.iptv.player.feature.channels

import android.content.Context
import android.widget.ImageView
import coil.ImageLoader
import coil.disk.DiskCache
import coil.load

/**
 * P2-3 (docs/04): the one [ImageLoader] the browse list uses for channel logos.
 *
 * Why a process-wide loader instead of `imageView.load(url)`'s default:
 * - **one disk cache**, configured here with a size cap, so logos survive a restart and a cold,
 *   offline start still paints the logos it already fetched (docs/01 F5 wants logos, not a flash of
 *   placeholders every launch);
 * - **one OkHttp client and one memory cache** for 658 rows, instead of the lazy default loader each
 *   call would otherwise build.
 *
 * The three "must not" clauses of the card are handled by Coil rather than by us:
 * - *does not block the list* — `load()` is asynchronous and cancels the in-flight request when the
 *   row is recycled, so scrolling never waits on an image (docs/02 §8.4's scroll budget);
 * - *does not crash with no network* — a failed fetch is a callback, never a throw;
 * - *falls back to the placeholder* — the holder keeps the initial-letter drawable behind the
 *   ImageView and clears the image on error, so a failure just reveals the placeholder. The failure
 *   is reported through [onError] so the caller can log it (P2-3 item 4: "按事件码记录").
 */
object ChannelLogoLoader {

    /** 64 MB: a few thousand small PNGs, and small next to the app's own media budget (docs/02 §8.4). */
    const val DISK_CACHE_BYTES: Long = 64L * 1024 * 1024

    const val DISK_CACHE_DIR = "channel-logos"

    @Volatile
    private var instance: ImageLoader? = null

    /** The shared loader. Safe to call from any thread; built once per process. */
    fun loader(context: Context): ImageLoader =
        instance ?: synchronized(this) {
            instance ?: build(context.applicationContext).also { instance = it }
        }

    private fun build(appContext: Context): ImageLoader = ImageLoader.Builder(appContext)
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve(DISK_CACHE_DIR))
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        // No crossfade: an animation per row is pure work inside the scroll budget, and the logos
        // swap at the same instant as the rest of the bound row.
        .crossfade(false)
        // A logo host's cache headers are not the app's contract; honouring them would make an
        // offline start miss a logo the disk cache already holds.
        .respectCacheHeaders(false)
        .build()

    /**
     * Binds [rawUrl] into [target], or clears it and leaves the placeholder showing.
     *
     * @param onError called (off the main thread is possible) when an attempted fetch fails, with the
     *   URL and the cause, so the caller logs one event per distinct failure.
     */
    fun bind(target: ImageView, rawUrl: String?, onError: (String, Throwable?) -> Unit) {
        val url = LogoPolicy.normalize(rawUrl)
        if (url == null) {
            target.tag = null
            target.setImageDrawable(null)
            return
        }
        val key = LogoPolicy.cacheKey(url)
        // The recycle guard: a recycled holder runs this again with a new key, and the failed
        // request's callback would otherwise clear an image that belongs to a different channel.
        target.tag = key
        target.load(url, loader(target.context)) {
            memoryCacheKey(key)
            diskCacheKey(key)
            crossfade(false)
            listener(
                onError = { _, result ->
                    if (target.tag == key) target.setImageDrawable(null)
                    onError(url, result.throwable)
                },
                onSuccess = { _, _ -> Unit },
            )
        }
    }

    /** Test seam: forgets the cached loader so a unit test can build a fresh one. */
    fun resetForTests() {
        synchronized(this) { instance = null }
    }
}
