package ilab.iptv.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Empty entry point for P0-1: proves the module graph, Hilt/KSP and the TV intent-filter all
 * assemble. Real navigation lands in P1 (docs/04).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
