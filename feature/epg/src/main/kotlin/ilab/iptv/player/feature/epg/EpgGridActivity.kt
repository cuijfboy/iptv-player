package ilab.iptv.player.feature.epg

import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.ui.epg.EpgContract
import ilab.iptv.player.core.ui.player.PlayerContract
import ilab.iptv.player.feature.epg.grid.EpgDetailPresenter
import ilab.iptv.player.feature.epg.grid.PerfSamplingPolicy
import ilab.iptv.player.feature.epg.grid.TimeAxis
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * The P3-1 EPG grid screen (docs/02 §8.1: `BrowseActivity → EpgGridFragment`; the fragment split waits for
 * the real navigation pass, so this is an activity for the same reason `BrowseActivity` is, and it is
 * reached through [EpgContract] so the browse feature never sees this class).
 *
 * The screen renders the state, forwards keys to the grid's own listener, and owns the two things the
 * grid must not: the **detail layer** and the **`PERF_EPG_GRID` event**.
 */
@AndroidEntryPoint
class EpgGridActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    private val viewModel: EpgGridViewModel by viewModels()
    private val timeAxis = TimeAxis()

    private lateinit var grid: EpgGridView
    private lateinit var header: TextView

    private var lastState: EpgGridUiState? = null
    private var requestedChannelId: Long? = null
    private var focusedChannelId: Long? = null

    /**
     * The detail layer is a dialog window, so Android closes it before this Activity sees BACK. The
     * reference is kept anyway (P3-7 items 1/4): the callback below is the fallback for the window
     * ordering the dialog cannot guarantee, and closing it must put focus back on the grid.
     */
    private var detailDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg_grid)

        requestedChannelId = EpgContract.readChannelId(intent)
        grid = findViewById(R.id.epg_grid)
        header = findViewById(R.id.epg_header)
        grid.listener = object : EpgGridView.Listener {
            override fun onSelectionChanged(rowIndex: Int, timeMs: Long, channelId: Long?) {
                focusedChannelId = channelId
                renderHeader()
            }

            override fun onCursorMoved(timeMs: Long) {
                viewModel.onCursorMoved(timeMs)
            }

            override fun onProgrammeActivated(
                rowIndex: Int,
                channelId: Long,
                channelName: String,
                programmeId: Long?,
                timeMs: Long,
            ) {
                showDetail(channelId, channelName, programmeId, timeMs)
            }

            override fun onVisibleRowsChanged(firstRow: Int, lastRow: Int) {
                viewModel.onVisibleRows(firstRow, lastRow)
            }
        }

        lifecycleScope.launch {
            viewModel.uiState.collect { state ->
                lastState = state
                grid.submit(state)
                renderHeader()
                // The browse screen's "show me this channel" only makes sense once the table is in.
                val requested = requestedChannelId
                if (requested != null && state.loaded) {
                    requestedChannelId = null
                    grid.selectChannel(requested, state.nowMs)
                }
                grid.requestFocus()
            }
        }

        // P3-7 item 1: the grid's only in-page level is the detail layer; the rule is stated in
        // EpgBackPolicy and unit-tested, and this callback is what makes it true even if the dialog
        // window did not consume the press.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when (EpgBackPolicy.decide(detailDialog?.isShowing == true)) {
                        EpgBackAction.CLOSE_DETAIL -> detailDialog?.dismiss()
                        EpgBackAction.LEAVE_SCREEN -> {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        logger.d(
            category = LogCategory.UI,
            code = EventCodes.UI_SCREEN_OPEN,
            message = "epg grid opened",
            fields = mapOf("screen" to SCREEN_NAME),
        )
        grid.beginPerfSampling()
        grid.requestFocus()
    }

    override fun onPause() {
        reportGridPerf()
        super.onPause()
    }

    /**
     * Work order item 4. The description only appears when the guide has one: an empty line is honest,
     * "null" is not.
     */
    private fun showDetail(channelId: Long, channelName: String, programmeId: Long?, timeMs: Long) {
        val programme = programmeId?.let { id ->
            lastState?.programmesByChannel?.get(channelId)?.firstOrNull { it.id == id }
        }
        val detail = EpgDetailPresenter.present(
            programme = programme,
            channelName = channelName,
            atMs = timeMs,
            timeAxis = timeAxis,
            noProgrammeTitle = getString(R.string.epg_detail_no_programme),
            crossDaySuffix = getString(R.string.epg_detail_next_day),
        )
        val body = buildString {
            append(detail.channelName)
            append('\n')
            append(detail.timeRange)
            detail.description?.let {
                append("\n\n")
                append(it)
            }
        }
        detailDialog = AlertDialog.Builder(this)
            .setTitle(detail.title)
            .setMessage(body)
            .setPositiveButton(R.string.epg_detail_watch) { _, _ ->
                startActivity(PlayerContract.intent(this, channelId))
            }
            .setNegativeButton(R.string.epg_detail_close, null)
            // P3-7 item 2: leaving the detail layer must leave the remote where it was — on the grid,
            // on the same row and programme.
            .setOnDismissListener {
                detailDialog = null
                grid.requestFocus()
            }
            .show()
    }

    private fun renderHeader() {
        val state = lastState
        header.text = if (state == null || !state.loaded) {
            getString(R.string.epg_loading)
        } else {
            getString(
                R.string.epg_header,
                state.channels.size,
                state.loadedChannelIds.size,
                windowLabel(state),
            ) + (focusedChannelId?.let { id ->
                state.channels.firstOrNull { it.id == id }?.let { "\n" + getString(R.string.epg_focus, it.name) }
            } ?: "")
        }
    }

    private fun windowLabel(state: EpgGridUiState): String =
        "${timeAxis.dayLabel(state.window.fromMs)} ${timeAxis.label(state.window.fromMs)}–" +
            "${timeAxis.label(state.window.toMs)}"

    /**
     * docs/02 §8.3: `PERF_EPG_GRID` (fps / drawnBlocks / layoutCacheHit / scrollMs) in debug for every
     * session, in release for a sampled tenth. `p95ms` and the drawn-block tail ride along because the
     * average is exactly the number that hides a lost virtualisation.
     */
    private fun reportGridPerf() {
        val summary = grid.endPerfSampling() ?: return
        if (summary.frames == 0) return
        val debugBuild = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!PerfSamplingPolicy.shouldReport(debugBuild, Random.nextDouble())) return
        logger.d(
            category = LogCategory.PERF,
            code = EventCodes.PERF_EPG_GRID,
            message = "epg grid frame sample",
            fields = mapOf(
                "screen" to SCREEN_NAME,
                "fps" to summary.avgFps.toInt(),
                "drawnBlocks" to summary.drawnBlocksP95,
                "drawnBlocksAvg" to summary.drawnBlocksAvg.toInt(),
                "drawnBlocksMax" to summary.drawnBlocksMax,
                "layoutCacheHit" to summary.textCacheHitRate,
                "layoutCacheHits" to summary.textCacheHits,
                "layoutCacheMisses" to summary.textCacheMisses,
                "virtualizationSkipRatio" to summary.virtualizationSkipRatio,
                "frames" to summary.frames,
                "p95ms" to summary.frameIntervalP95Ms,
                "scrollMs" to summary.sampledMs,
            ),
        )
    }

    private companion object {
        const val SCREEN_NAME = "EpgGrid"
    }
}
