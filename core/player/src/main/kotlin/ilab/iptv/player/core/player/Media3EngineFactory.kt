package ilab.iptv.player.core.player

import android.content.Context
import ilab.iptv.player.core.model.EngineCapability
import kotlinx.coroutines.CoroutineDispatcher

/**
 * docs/02 §7.1 main engine: Media3 ExoPlayer. `caps` is what it can attempt ([Media3Capabilities.BASE]);
 * AC3 passthrough is added by the engine after the real device probe (§7.4).
 */
class Media3EngineFactory(
    private val context: Context,
    private val tuning: EngineTuning = EngineTuning.LIVE_DEFAULT,
) : PlayerEngineFactory {

    override val id: String = ID
    override val caps: Set<EngineCapability> = Media3Capabilities.BASE

    override fun create(dispatcher: CoroutineDispatcher): PlayerEngine =
        Media3Engine(context = context.applicationContext, dispatcher = dispatcher, tuning = tuning)

    companion object {
        const val ID = "media3"
    }
}
