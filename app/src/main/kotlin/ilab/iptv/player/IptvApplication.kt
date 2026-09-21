package ilab.iptv.player

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt root (docs/02 §3.1). All extension points (sources, scorers, player engines, log sinks)
 * are bound here through `@IntoSet` multibindings.
 */
@HiltAndroidApp
class IptvApplication : Application()
