package ilab.iptv.player.feature.player

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.model.AspectRatioMode
import ilab.iptv.player.core.model.InfoBarState
import ilab.iptv.player.core.model.PlaybackUiState
import ilab.iptv.player.core.player.AudioTrackState
import ilab.iptv.player.core.player.OverscanPolicy
import ilab.iptv.player.core.player.SubtitleTrackState
import ilab.iptv.player.core.ui.player.PlayerContract
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Full-screen playback screen (P1-4 views + P1-5 remote): a `SurfaceView` handed to the session, an
 * info bar that fades out after [InfoBarVisibility.INFO_BAR_TIMEOUT_MS] of no remote input, the four
 * display modes, a status overlay for starting/buffering, a readable failure overlay with a retry
 * entry point, and the fail-over status line (P1-5 item 4).
 *
 * OWNERSHIP (docs/02 §7.4): the Activity owns the views; the engine never creates one. The surface is
 * attached on `surfaceCreated`/`surfaceChanged` and detached (`null`) on `surfaceDestroyed`, which per
 * §7.2 P3 means "stop rendering, keep the audio" — entering or leaving this screen never creates or
 * releases an engine, and a channel switch reuses the same instance (§6.2).
 *
 * KEY DISCIPLINE (§8.2) — this screen's keymap is the frozen one, not the browse screen's:
 *  - `DPAD_UP` / `CHANNEL_UP` = next channel, `DPAD_DOWN` / `CHANNEL_DOWN` = previous channel, inside
 *    the current group and in the browse list's order ([ChannelSwitchPlanner], P1-5 item 1). These
 *    keys therefore do NOT move focus: §8.2 says "播放页的上下键不做焦点移动（直接换台）", and P1-4's
 *    up/down focus wiring is deliberately replaced here. The bar's "画幅" control (and the failure
 *    card's "重试") is reached with LEFT/RIGHT instead, so no control is stranded.
 *  - `0`–`9` = jump to a channel number through the frozen 2 s window ([ChannelNumberBuffer]); the
 *    digits in progress are echoed on the info bar's status line.
 *  - `MEDIA_PLAY` / `MEDIA_PAUSE` = the playback controls of §8.2.
 *  - `BACK` = hierarchy: info bar showing → take it down; again → leave playback.
 */
@AndroidEntryPoint
// `AspectRatioFrameLayout` (media3-ui) is @UnstableApi; it is the frozen view half of §7.4's display
// switch, so the opt-in is deliberate and local to this screen.
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), SurfaceHolder.Callback {

    @Inject
    lateinit var logger: Logger

    private val viewModel: PlayerViewModel by viewModels()
    private val visibility = InfoBarVisibility()
    private val backPolicy = PlayerBackPolicy()
    private val numberBuffer = ChannelNumberBuffer()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var root: View
    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var infoBar: View
    private lateinit var infoName: TextView
    private lateinit var infoLogo: TextView
    private lateinit var infoQuality: TextView
    private lateinit var infoNowNext: TextView
    private lateinit var infoStatus: TextView
    private lateinit var infoAspect: TextView
    private lateinit var infoAudio: TextView
    private lateinit var infoSubtitle: TextView
    private lateinit var infoOverscan: TextView
    private lateinit var statusGroup: View
    private lateinit var statusText: TextView
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: TextView

    private var channelId: Long? = null
    private var lastOverlay: PlayerOverlay? = null
    private var lastInfo: InfoBarState? = null

    /**
     * A one-shot line for the info bar ("当前流没有字幕轨"), shown like a fail-over hint and dropped
     * with the bar. P1-5 already froze that line for "what the state machine is doing"; a greyed
     * entry point has to say why it is greyed, and this is the cheapest honest place for it.
     */
    private var transientHint: String? = null

    private val hideRunnable = Runnable { if (visibility.tick(now())) hideInfoBar() }
    private val commitNumberRunnable = Runnable { commitNumber() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full screen + always-on (§8): the player is the one screen that must not dim or sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(R.layout.activity_player)
        bindViews()

        val input = PlayerContract.read(intent)
        if (input == null) {
            // A caller that skipped the contract: fail visibly instead of showing a black screen.
            logger.w(
                LogCategory.UI,
                EventCodes.UI_SCREEN_OPEN,
                "player opened without a channel id",
                mapOf("screen" to SCREEN_NAME),
            )
            finish()
            return
        }
        channelId = input.channelId
        // P1-7 item 1: the playback foreground service starts with the screen. It keeps the process
        // alive (and the audio running) when the user goes back to the launcher mid-channel, and it is
        // what makes the remote's play/pause key reach the stream through the MediaSession.
        PlaybackService.start(this)
        surfaceView.holder.addCallback(this)
        root.requestFocus()
        applyAspectRatio(playbackAspect())

        infoAspect.setOnClickListener {
            val mode = viewModel.cycleAspectRatio()
            applyAspectRatio(mode)
            showInfoBar()
        }
        // P3-3 items 1–3: the three track/display controls. Each opens its own list; a list with
        // nothing to choose explains itself on the status line instead of opening empty.
        infoAudio.setOnClickListener { openAudioMenu() }
        infoSubtitle.setOnClickListener { openSubtitleMenu() }
        infoOverscan.setOnClickListener { openOverscanMenu() }
        retryButton.setOnClickListener {
            viewModel.retry()
            showInfoBar()
        }

        applyOverscan(viewModel.overscanIndex.value)
        lifecycleScope.launch {
            viewModel.overscanIndex.collect { applyOverscan(it) }
        }
        lifecycleScope.launch {
            viewModel.playback.collect { state ->
                val previousChannelId = channelId
                val previousHint = lastInfo?.failoverHint
                channelId = state.channelId ?: channelId
                lastInfo = state.infoBar
                renderInfoBar(state.infoBar)
                renderTracks(state)
                applyAspectRatio(state.aspectRatio)
                // A switch and a fail-over are both worth surfacing: the user pressed UP (or the
                // source died) and the screen must say so without waiting for a key press.
                val switchedChannel = state.channelId != null && state.channelId != previousChannelId
                val hint = state.infoBar?.failoverHint
                val newHint = hint != null && hint != previousHint
                // A new hint re-arms the bar even when it is already up, so "已切换备用源" gets its own
                // full 5 s to be read instead of inheriting what is left of the previous message.
                if (switchedChannel || newHint) showInfoBar()
            }
        }
        lifecycleScope.launch {
            viewModel.fault.collect { renderOverlay(PlayerOverlayState.of(viewModel.playback.value, it)) }
        }
        lifecycleScope.launch {
            viewModel.playback.collect { renderOverlay(PlayerOverlayState.of(it, viewModel.fault.value)) }
        }

        viewModel.start(input)
    }

    override fun onResume() {
        super.onResume()
        logger.d(
            LogCategory.UI,
            EventCodes.UI_SCREEN_OPEN,
            "player screen opened",
            mapOf("screen" to SCREEN_NAME, "channelId" to channelId),
        )
        // The bar greets the user, then fades on its own — the same path as a remote key.
        showInfoBar()
    }

    override fun onPause() {
        hideInfoBar()
        super.onPause()
    }

    override fun onDestroy() {
        hideRunnable.let(mainHandler::removeCallbacks)
        commitNumberRunnable.let(mainHandler::removeCallbacks)
        if (isFinishing) {
            viewModel.onPlayerClosed()
            // P1-7 item 1: the other half of "停止播放时正确收尾" — the screen is gone for good, so the
            // foreground service and its notification go with it. (Pressing HOME does not finish the
            // Activity, which is exactly why playback survives it.)
            PlaybackService.stop(this)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- keys (§8.2, P1-5 item 1)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                when (backPolicy.onBack()) {
                    BackAction.HideOverlay -> hideInfoBar()
                    BackAction.ExitPlayer -> finishWithResult()
                }
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                showInfoBar()
                viewModel.switchChannel(NEXT_CHANNEL)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                showInfoBar()
                viewModel.switchChannel(PREVIOUS_CHANNEL)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                showInfoBar()
                viewModel.setPaused(false)
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                showInfoBar()
                viewModel.setPaused(true)
                return true
            }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            onDigitKey(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        // "任意遥控键唤出" (P1-4 item 2): every other key is activity, so the bar shows and re-arms.
        showInfoBar()
        return super.onKeyDown(keyCode, event)
    }

    /**
     * The 2 s digit window (docs/02 §8.2). The buffer decides, the handler schedules the commit, and
     * the info bar echoes what has been typed so the user sees the window working.
     */
    private fun onDigitKey(digit: Int) {
        showInfoBar()
        when (val decision = numberBuffer.onDigit(digit, now())) {
            is ChannelNumberBuffer.Decision.Commit -> {
                mainHandler.removeCallbacks(commitNumberRunnable)
                renderStatus()
                viewModel.jumpToNumber(decision.number)
            }

            is ChannelNumberBuffer.Decision.Pending -> {
                renderStatus()
                mainHandler.removeCallbacks(commitNumberRunnable)
                mainHandler.postDelayed(commitNumberRunnable, decision.remainingMs + HIDE_POLL_MS)
            }

            ChannelNumberBuffer.Decision.None -> Unit
        }
    }

    private fun commitNumber() {
        when (val decision = numberBuffer.onTick(now())) {
            is ChannelNumberBuffer.Decision.Commit -> {
                renderStatus()
                viewModel.jumpToNumber(decision.number)
            }

            is ChannelNumberBuffer.Decision.Pending -> {
                renderStatus()
                mainHandler.postDelayed(commitNumberRunnable, decision.remainingMs + HIDE_POLL_MS)
            }

            ChannelNumberBuffer.Decision.None -> renderStatus()
        }
    }

    // ---------------------------------------------------------------- surface (§7.4)

    override fun surfaceCreated(holder: SurfaceHolder) {
        viewModel.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // The TV tears down and rebuilds the surface while the window settles (measured in P1-3), so
        // this is a re-attach, not a new session.
        viewModel.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        viewModel.attachSurface(null)
    }

    // ---------------------------------------------------------------- rendering

    private fun bindViews() {
        root = findViewById(R.id.player_root)
        frame = findViewById(R.id.player_frame)
        surfaceView = findViewById(R.id.player_surface)
        infoBar = findViewById(R.id.info_bar)
        infoName = findViewById(R.id.info_name)
        infoLogo = findViewById(R.id.info_logo)
        infoQuality = findViewById(R.id.info_quality)
        infoNowNext = findViewById(R.id.info_now_next)
        infoStatus = findViewById(R.id.info_status)
        infoAspect = findViewById(R.id.info_aspect)
        infoAudio = findViewById(R.id.info_audio)
        infoSubtitle = findViewById(R.id.info_subtitle)
        infoOverscan = findViewById(R.id.info_overscan)
        statusGroup = findViewById(R.id.player_status_group)
        statusText = findViewById(R.id.player_status)
        errorGroup = findViewById(R.id.player_error_group)
        errorText = findViewById(R.id.player_error)
        retryButton = findViewById(R.id.player_retry)
        // docs/02 §8.2 + P1-5: UP/DOWN switch channels, so the two controls of this screen are reached
        // with LEFT/RIGHT. Each control stays on itself vertically (the keys never reach focus search)
        // and hands focus back to the root horizontally, so a press sequence can never strand focus.
        infoAspect.nextFocusLeftId = root.id
        infoAspect.nextFocusRightId = root.id
        infoAspect.nextFocusUpId = infoAspect.id
        infoAspect.nextFocusDownId = infoAspect.id
        // P3-3: one horizontal chain for the bar's four controls. LEFT/RIGHT still enters the bar from
        // the video (docs/02 §8.2's frozen path to 画幅), and each further press walks to the next
        // control and finally back to the root — no key can strand focus, and nothing else moved.
        infoAspect.nextFocusLeftId = root.id
        infoAspect.nextFocusRightId = infoAudio.id
        infoAudio.nextFocusLeftId = infoAspect.id
        infoAudio.nextFocusRightId = infoSubtitle.id
        infoAudio.nextFocusUpId = infoAudio.id
        infoAudio.nextFocusDownId = infoAudio.id
        infoSubtitle.nextFocusLeftId = infoAudio.id
        infoSubtitle.nextFocusRightId = infoOverscan.id
        infoSubtitle.nextFocusUpId = infoSubtitle.id
        infoSubtitle.nextFocusDownId = infoSubtitle.id
        infoOverscan.nextFocusLeftId = infoSubtitle.id
        infoOverscan.nextFocusRightId = root.id
        infoOverscan.nextFocusUpId = infoOverscan.id
        infoOverscan.nextFocusDownId = infoOverscan.id
        root.nextFocusUpId = root.id
        root.nextFocusDownId = root.id
        root.nextFocusLeftId = root.id
        root.nextFocusRightId = root.id
        retryButton.nextFocusLeftId = root.id
        retryButton.nextFocusRightId = root.id
        retryButton.nextFocusUpId = retryButton.id
        retryButton.nextFocusDownId = retryButton.id
    }

    private fun renderInfoBar(info: InfoBarState?) {
        infoName.text = info?.channelName ?: getString(R.string.player_channel_unknown)
        infoLogo.text = info?.channelName?.trim()?.take(1)?.ifEmpty { "?" } ?: "?"
        infoQuality.text = info?.qualityLabel ?: getString(R.string.player_quality_pending)
        // P2-7: the real now/next replaces the P1-4 placeholder. When the channel has no EPG at all the
        // line is taken down instead of printing a stand-in — a channel without a guide is normal
        // (docs/02 §6.3), and a "待接入" label on screen is scaffolding, not information.
        val line = NowNextLabel.of(info?.nowNext)
        when (line.kind) {
            NowNextLabel.Kind.NOW -> {
                infoNowNext.visibility = View.VISIBLE
                infoNowNext.text = getString(R.string.player_now_next_now, line.title.orEmpty())
            }

            NowNextLabel.Kind.NEXT -> {
                infoNowNext.visibility = View.VISIBLE
                infoNowNext.text = getString(R.string.player_now_next_next, line.title.orEmpty())
            }

            NowNextLabel.Kind.NONE -> {
                infoNowNext.text = ""
                infoNowNext.visibility = View.GONE
            }
        }
        renderStatus()
    }

    /**
     * The status line: what the fail-over state machine is doing (P1-5 item 4), or the channel number
     * being typed into the 2 s digit window. Digits win while they are pending — the user is looking
     * at the number they just pressed.
     */
    private fun renderStatus() {
        val digits = numberBuffer.pendingDigits
        val hint = lastInfo?.failoverHint
        val text = when {
            digits.isNotEmpty() -> getString(R.string.player_channel_number, digits)
            transientHint != null -> transientHint
            hint != null -> hint
            else -> null
        }
        infoStatus.visibility = if (text == null) View.GONE else View.VISIBLE
        infoStatus.text = text ?: ""
    }

    /**
     * P3-3 items 1–2: the two track buttons follow the *stream*, not last round's channel — the
     * engine reports its tracks per media item, so a channel without subtitles greys the entry point
     * and one with a single audio track says so instead of offering a pointless menu.
     */
    private fun renderTracks(state: PlaybackUiState) {
        val audio = AudioTrackState(state.audioTracks, state.selectedAudioTrackId)
        infoAudio.text = getString(R.string.player_audio_format, audio.valueLabel())
        infoAudio.alpha = if (audio.enabled) 1f else DISABLED_ALPHA

        val subtitle = SubtitleTrackState(
            tracks = state.subtitleTracks,
            selectedId = state.selectedSubtitleTrackId,
            enabled = state.subtitlesEnabled,
        )
        infoSubtitle.text = getString(R.string.player_subtitle_format, subtitle.valueLabel())
        infoSubtitle.alpha = if (subtitle.available) 1f else DISABLED_ALPHA
    }

    /** P3-3 item 3: the scale step, applied to the video container (docs/02 §7.4, not the engine). */
    private fun applyOverscan(index: Int) {
        val scale = OverscanPolicy.scale(index)
        frame.scaleX = scale
        frame.scaleY = scale
        infoOverscan.text = getString(R.string.player_overscan_format, OverscanPolicy.label(index))
    }

    // ---------------------------------------------------------------- P3-3 menus

    private fun openAudioMenu() {
        val state = viewModel.playback.value
        val menu = AudioTrackState(state.audioTracks, state.selectedAudioTrackId)
        if (!menu.enabled) {
            showBarHint(getString(R.string.player_audio_single))
            return
        }
        val options = menu.options()
        alertRows(
            title = getString(R.string.player_audio_title),
            rows = options.map { getString(trackRowLabel(it.selected), it.label) },
        ) { which ->
            if (!viewModel.selectAudioTrack(options[which].id)) {
                showBarHint(getString(R.string.player_track_switch_failed))
            }
        }
    }

    private fun openSubtitleMenu() {
        val state = viewModel.playback.value
        val menu = SubtitleTrackState(
            tracks = state.subtitleTracks,
            selectedId = state.selectedSubtitleTrackId,
            enabled = state.subtitlesEnabled,
        )
        if (!menu.available) {
            // The P3-3 wording: "无可选时入口置灰并说明" — the entry point is dimmed above, and this is
            // the explanation when the user presses it anyway.
            showBarHint(getString(R.string.player_subtitle_none))
            return
        }
        val options = menu.options()
        alertRows(
            title = getString(R.string.player_subtitle_title),
            rows = options.map { getString(trackRowLabel(it.selected), it.label) },
        ) { which ->
            if (!viewModel.selectSubtitleTrack(options[which].id)) {
                showBarHint(getString(R.string.player_track_switch_failed))
            }
        }
    }

    private fun openOverscanMenu() {
        alertRows(
            title = getString(
                R.string.player_overscan_title,
                OverscanPolicy.label(viewModel.overscanIndex.value),
            ),
            rows = listOf(
                getString(R.string.player_overscan_zoom_in),
                getString(R.string.player_overscan_zoom_out),
                getString(R.string.player_overscan_reset),
            ),
        ) { which ->
            when (which) {
                0 -> viewModel.moveOverscan(+1)
                1 -> viewModel.moveOverscan(-1)
                else -> viewModel.resetOverscan()
            }
        }
    }

    /** The framework's own list dialog: `setItems` keeps the remote's focus path (the BUG-013 lesson). */
    private fun alertRows(title: String, rows: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(rows.toTypedArray()) { _, which -> onPick(which) }
            .setOnDismissListener { showInfoBar() }
            .show()
    }

    private fun trackRowLabel(selected: Boolean): Int =
        if (selected) R.string.player_track_selected else R.string.player_track_unselected

    /** Puts a one-shot explanation on the status line; the bar shows and re-arms so it can be read. */
    private fun showBarHint(text: String) {
        transientHint = text
        showInfoBar()
    }

    private fun applyAspectRatio(mode: AspectRatioMode) {
        frame.resizeMode = AspectRatioCycle.resizeMode(mode)
        // 0 = use the video's own ratio (media3-ui contract); only FIXED_4_3 pins one.
        frame.setAspectRatio(AspectRatioCycle.fixedAspectRatio(mode) ?: 0f)
        infoAspect.text = getString(R.string.player_aspect_format, AspectRatioCycle.label(mode))
    }

    private fun playbackAspect(): AspectRatioMode = viewModel.playback.value.aspectRatio

    private fun renderOverlay(overlay: PlayerOverlay) {
        if (lastOverlay == overlay) return
        lastOverlay = overlay
        surfaceView.visibility = if (overlay.kind == PlayerOverlay.Kind.ERROR) View.INVISIBLE else View.VISIBLE
        when (overlay.kind) {
            PlayerOverlay.Kind.NONE -> {
                statusGroup.visibility = View.GONE
                errorGroup.visibility = View.GONE
                // No failure card above the bar: LEFT/RIGHT goes to the bar's own control.
                root.nextFocusLeftId = infoAspect.id
                root.nextFocusRightId = infoAspect.id
            }

            PlayerOverlay.Kind.STARTING, PlayerOverlay.Kind.BUFFERING -> {
                statusText.text = overlay.message
                statusGroup.visibility = View.VISIBLE
                errorGroup.visibility = View.GONE
                root.nextFocusLeftId = infoAspect.id
                root.nextFocusRightId = infoAspect.id
            }

            PlayerOverlay.Kind.ERROR -> {
                statusGroup.visibility = View.GONE
                errorText.text = overlay.message
                errorGroup.visibility = View.VISIBLE
                // The failure card sits above the info bar; LEFT/RIGHT reaches either of them, so the
                // retry entry point stays reachable without an up/down key that switches channels.
                root.nextFocusLeftId = retryButton.id
                root.nextFocusRightId = retryButton.id
                // The retry entry point takes focus so one OK press is enough (§8.2: 失败要有重试入口).
                retryButton.post { retryButton.requestFocus() }
            }
        }
    }

    // ---------------------------------------------------------------- info bar lifecycle

    private fun showInfoBar() {
        visibility.show(now())
        backPolicy.onOverlayShown()
        // A key can land while the bar is still fading out. The old code only re-showed a bar that was
        // GONE, so that key left the bar invisible and the pending fade-out's end action then set it
        // GONE for good (found on the device with the digit window, P1-5 §6.3). Re-arm whenever the bar
        // is not fully opaque, and let the fade-out's end action check the logical state before hiding.
        if (infoBar.visibility != View.VISIBLE || infoBar.alpha < 1f) {
            infoBar.animate().cancel()
            if (infoBar.visibility != View.VISIBLE) infoBar.alpha = 0f
            infoBar.visibility = View.VISIBLE
            infoBar.animate()
                .alpha(1f)
                .setDuration(InfoBarVisibility.INFO_BAR_FADE_MS)
                .withEndAction(null)
                .start()
        }
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, InfoBarVisibility.INFO_BAR_TIMEOUT_MS + HIDE_POLL_MS)
        renderStatus()
    }

    private fun hideInfoBar() {
        visibility.hide()
        backPolicy.onOverlayHidden()
        mainHandler.removeCallbacks(hideRunnable)
        numberBuffer.reset()
        mainHandler.removeCallbacks(commitNumberRunnable)
        // A one-shot hint belongs to the bar it was shown in: the next time the bar comes up it is
        // either a new message or nothing, never a stale "当前流没有字幕轨" over a different channel.
        transientHint = null
        if (infoBar.hasFocus()) root.requestFocus()
        infoBar.animate().cancel()
        infoBar.animate()
            .alpha(0f)
            .setDuration(InfoBarVisibility.INFO_BAR_FADE_MS)
            // Only hide for good if the bar is *still* supposed to be down: a key during the fade
            // re-shows it, and a stale end action must not undo that (race found in P1-5 §6.3).
            .withEndAction { if (!visibility.visible) infoBar.visibility = View.GONE }
            .start()
    }

    private fun finishWithResult() {
        // docs/02 §8.1: the browse screen restores focus by channelId on RESULT_OK.
        channelId?.let { setResult(RESULT_OK, PlayerContract.result(it)) }
        finish()
    }

    private fun now(): Long = SystemClock.uptimeMillis()

    private companion object {
        const val SCREEN_NAME = "Player"

        /** One extra poll so a timer sees a time strictly past its deadline (no same-ms race). */
        const val HIDE_POLL_MS = 16L

        /** §8.2: UP = next channel, DOWN = previous, inside the current group. */
        const val NEXT_CHANNEL = 1
        const val PREVIOUS_CHANNEL = -1

        /** P3-3: how far a greyed (but still readable/pressable) entry point is dimmed. */
        const val DISABLED_ALPHA = 0.45f
    }
}
