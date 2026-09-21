package ilab.iptv.player.feature.channels

import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.ui.player.PlayerContract
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The P1-2 browse screen: a grouped, virtualized channel list with a remote-driven focus path
 * (docs/02 §8.1/§8.2) and a live frame-rate readout for the §8.4 budget.
 *
 * P1-4 adds the entry point: OK/Enter on a row opens the player through [PlayerContract] (an action,
 * not a class reference — docs/02 §3.2 forbids feature → feature dependencies), and the result is
 * used to put focus back on the channel that was playing (§8.1's 播放返回 contract).
 *
 * Scope honesty: there is still no search (P3-2) and no EPG column (P3-1). The header line exists
 * because the fps target is part of the acceptance criteria and a screenshot has to carry the
 * numbers, not just the layout.
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

    /**
     * docs/02 §8.1 播放返回: `RESULT_OK` carries the channel id and the browse screen restores focus on
     * it — "找不到则回到分组首项" is the fallback below.
     */
    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val channelId = result.data
            ?.takeIf { result.resultCode == RESULT_OK }
            ?.getLongExtra(PlayerContract.EXTRA_RESULT_CHANNEL_ID, MISSING_CHANNEL_ID)
            ?.takeIf { it != MISSING_CHANNEL_ID }
        restoreFocusOrFirstRow(channelId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)

        header = findViewById(R.id.browse_header)
        list = findViewById(R.id.channel_list)
        adapter = ChannelListAdapter(
            onChannelFocused = { item -> onChannelFocused(item) },
            onChannelSelected = { item -> openPlayer(item) },
        )
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

    /** OK/Enter on a row (P1-4 item 1: "列表项 OK/Enter 打开播放"). */
    private fun openPlayer(item: ChannelListRow.ChannelItem) {
        playerLauncher.launch(PlayerContract.intent(this, channelId = item.channelId))
    }

    /**
     * Puts the remote back where the user left it. The row may not exist any more (the catalog can be
     * re-loaded while the player is up), in which case focus goes to the first channel row — the
     * documented fallback of docs/02 §8.1.
     */
    private fun restoreFocusOrFirstRow(channelId: Long?) {
        val rows = adapter.currentList
        val position = when {
            channelId != null -> rows.indexOfFirst {
                it is ChannelListRow.ChannelItem && it.channelId == channelId
            }

            else -> -1
        }
        val target = if (position >= 0) position else rows.indexOfFirst { it is ChannelListRow.ChannelItem }
        if (target < 0) return
        list.scrollToPosition(target)
        list.post { list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() }
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
        /** No channel id came back: sentinel from the framework's default long. */
        const val MISSING_CHANNEL_ID = Long.MIN_VALUE
    }
}
