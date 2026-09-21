package ilab.iptv.player.feature.player

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
    private lateinit var statusGroup: View
    private lateinit var statusText: TextView
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: TextView

    private var channelId: Long? = null
    private var lastOverlay: PlayerOverlay? = null
    private var lastInfo: InfoBarState? = null

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
        surfaceView.holder.addCallback(this)
        root.requestFocus()
        applyAspectRatio(playbackAspect())

        infoAspect.setOnClickListener {
            val mode = viewModel.cycleAspectRatio()
            applyAspectRatio(mode)
            showInfoBar()
        }
        retryButton.setOnClickListener {
            viewModel.retry()
            showInfoBar()
        }

        lifecycleScope.launch {
            viewModel.playback.collect { state ->
                val previousChannelId = channelId
                val previousHint = lastInfo?.failoverHint
                channelId = state.channelId ?: channelId
                lastInfo = state.infoBar
                renderInfoBar(state.infoBar)
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
        if (isFinishing) viewModel.onPlayerClosed()
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
        // EPG now&next is P2-7; the slot is on screen from P1-4 so the layout is already proven.
        infoNowNext.text = info?.nowNext?.now?.title ?: getString(R.string.player_now_next_placeholder)
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
            hint != null -> hint
            else -> null
        }
        infoStatus.visibility = if (text == null) View.GONE else View.VISIBLE
        infoStatus.text = text ?: ""
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
    }
}
