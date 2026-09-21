package ilab.iptv.player.core.data.catalog

/**
 * The one thing the repositories need from the loader: "make sure a catalog is in the store".
 *
 * It is an interface so the repository has no Android dependency of its own (unit tests drive it
 * with fixture text through a fake, and the real `AssetManager` read stays in
 * [ChannelCatalogLoader]) — and so the trigger for the first load can move (WorkManager, a source
 * refresh, the wizard) without touching a repository.
 */
interface CatalogBootstrapper {

    /** Loads on the first call, no-op afterwards. Returns the report, or null when loading failed. */
    suspend fun ensureLoaded(): CatalogLoadReport?
}
