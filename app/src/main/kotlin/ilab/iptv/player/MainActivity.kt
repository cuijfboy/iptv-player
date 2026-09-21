package ilab.iptv.player

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.log.ui.LogConsoleActivity
import javax.inject.Inject

/**
 * Entry point: P0-1 proved the module graph, Hilt/KSP and the TV intent-filter assemble; P0-6 adds
 * the one route that exists so far — the log/device console (docs/03 §7.2, L1). Real navigation
 * (channels, player, EPG) lands in P1 (docs/04).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.open_log_console).setOnClickListener {
            startActivity(Intent(this, LogConsoleActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // DEBUG level per docs/03 §3.3; visible on the console after switching to DEBUG.
        logger.d(
            category = LogCategory.UI,
            code = EventCodes.UI_SCREEN_OPEN,
            message = "main screen resumed",
            fields = mapOf("screen" to "Main"),
        )
    }
}
