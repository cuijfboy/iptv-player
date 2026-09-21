package ilab.iptv.player.feature.channels

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The P1-2 browse screen: a grouped, virtualized channel list with a remote-driven focus path
 * (docs/02 §8.1/§8.2) and a live frame-rate readout for the §8.4 budget.
 *
 * Scope honesty: this is the *list* stage. Selecting a channel does nothing yet (playback is P1-3/P1-4,
 * dev-B), there is no search (P3-2) and no EPG column (P3-1). The header line exists because the fps
 * target is part of the acceptance criteria and a screenshot has to carry the numbers, not just the
 * layout.
 */
@AndroidEntryPoint
class BrowseActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    private val viewModel: ChannelListViewModel by viewModels()

    private lateinit var list: RecyclerView
    private lateinit var header: TextView
    private lateinit var adapter: ChannelListAdapter

    private var lastState: ChannelListUiState = ChannelListUiState.Loading
    private var lastStats: FrameRateMonitor.Stats? = null
    private var focused: ChannelListRow.ChannelItem? = null
    /** True once the list is on screen; before that the samples are activity start-up, not scrolling. */
    private var listRendered: Boolean = false

    private val frameRate = FrameRateMonitor { stats ->
        lastStats = stats
        renderHeader()
        // docs/03 §3.3: `UI_FRAME_JANK` (WARN) is the registered code for "frame time over budget".
        // Start-up frames are excluded on purpose — the acceptance target is *scroll* fps (§8.4).
        if (listRendered && stats.frames > 0 && stats.p95Ms > JANK_THRESHOLD_MS) {
            logger.w(
                category = LogCategory.UI,
                code = EventCodes.UI_FRAME_JANK,
                message = "channel list scroll over frame budget",
                fields = mapOf(
                    "screen" to SCREEN_NAME,
                    "frames" to stats.frames,
                    "p95ms" to stats.p95Ms,
                    "fps" to stats.fps,
                ),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)

        header = findViewById(R.id.browse_header)
        list = findViewById(R.id.channel_list)
        adapter = ChannelListAdapter { item -> onChannelFocused(item) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.setHasFixedSize(true)
        // docs/02 §8.2: focus stays inside the list, so the key path is unambiguous (no tab bar yet).
        list.descendantFocusability = RecyclerView.FOCUS_AFTER_DESCENDANTS

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                lastState = state
                if (!listRendered && state.rows.isNotEmpty()) {
                    // Drop everything sampled while the screen was still empty, so the HUD reports the
                    // list's frame rate rather than the activity's cold start (§8.5 measuring口径).
                    listRendered = true
                    frameRate.reset()
                }
                adapter.submitList(state.rows) {
                    // First render: put the remote somewhere useful instead of leaving focus nowhere.
                    if (!list.hasFocus() && list.findFocus() == null) {
                        list.post { list.getChildAt(0)?.requestFocus() }
                    }
                }
                renderHeader()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        logger.d(
            category = LogCategory.UI,
            code = EventCodes.UI_SCREEN_OPEN,
            message = "channel list opened",
            fields = mapOf("screen" to SCREEN_NAME),
        )
        frameRate.reset()
        frameRate.start()
    }

    override fun onPause() {
        frameRate.stop()
        super.onPause()
    }

    private fun onChannelFocused(item: ChannelListRow.ChannelItem) {
        focused = item
        renderHeader()
    }

    /**
     * The on-screen proof line: how many channels/groups/streams the repository produced, the frame
     * statistics over the current scroll window, and which row has focus.
     */
    private fun renderHeader() {
        val stats = lastStats
        val frames = if (stats == null || stats.frames == 0) {
            getString(R.string.browse_frames_pending)
        } else {
            getString(
                R.string.browse_frames,
                stats.frames,
                stats.p50Ms,
                stats.p95Ms,
                stats.maxMs,
                stats.fps,
                stats.jankyFrames,
            )
        }
        header.text = if (!lastState.loaded) {
            getString(R.string.browse_loading)
        } else {
            getString(
                R.string.browse_summary,
                lastState.channelCount,
                lastState.groupCount,
                lastState.streamCount,
                frames,
            ) + (focused?.let { "\n" + getString(R.string.browse_focus, it.number, it.name) } ?: "")
        }
    }

    private companion object {
        const val SCREEN_NAME = "Browse"
        /** docs/02 §8.5: 60 Hz budget + documented 0.3 ms tolerance. */
        const val JANK_THRESHOLD_MS = 17.0
    }
}
