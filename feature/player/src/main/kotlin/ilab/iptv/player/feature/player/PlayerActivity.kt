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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.common.util.UnstableApi
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
 * Full-screen playback screen (P1-4): a `SurfaceView` handed to the session, an info bar that fades
 * out after [InfoBarVisibility.INFO_BAR_TIMEOUT_MS] of no remote input, the four display modes, a
 * status overlay for starting/buffering and a readable failure overlay with a retry entry point.
 *
 * OWNERSHIP (docs/02 §7.4): the Activity owns the views; the engine never creates one. The surface
 * is attached on `surfaceCreated`/`surfaceChanged` and detached (`null`) on `surfaceDestroyed`, which
 * per §7.2 P3 means "stop rendering, keep the audio" — entering or leaving this screen never creates
 * or releases an engine.
 *
 * KEY DISCIPLINE (§8.2): BACK is the only key with a hierarchy — info bar showing → take it down;
 * again → leave playback. Every other remote key wakes the info bar. Focus is never left nowhere:
 * the root stays focusable, DOWN/UP reach the "画幅" control while the bar is up, and LEFT/RIGHT on
 * that control stay on it (up/down channel switching is P1-5 and is deliberately not wired here).
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var root: View
    private lateinit var frame: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var infoBar: View
    private lateinit var infoName: TextView
    private lateinit var infoLogo: TextView
    private lateinit var infoQuality: TextView
    private lateinit var infoNowNext: TextView
    private lateinit var infoAspect: TextView
    private lateinit var statusGroup: View
    private lateinit var statusText: TextView
    private lateinit var errorGroup: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: TextView

    private var channelId: Long? = null
    private var lastOverlay: PlayerOverlay? = null

    private val hideRunnable = Runnable { if (visibility.tick(now())) hideInfoBar() }

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
                channelId = state.channelId ?: channelId
                renderInfoBar(state.infoBar)
                applyAspectRatio(state.aspectRatio)
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
        if (isFinishing) viewModel.onPlayerClosed()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- keys (§8.2)

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            when (backPolicy.onBack()) {
                BackAction.HideOverlay -> hideInfoBar()
                BackAction.ExitPlayer -> finishWithResult()
            }
            return true
        }
        // "任意遥控键唤出" (P1-4 item 2): every other key is activity, so the bar shows and re-arms.
        showInfoBar()
        return super.onKeyDown(keyCode, event)
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
        infoAspect = findViewById(R.id.info_aspect)
        statusGroup = findViewById(R.id.player_status_group)
        statusText = findViewById(R.id.player_status)
        errorGroup = findViewById(R.id.player_error_group)
        errorText = findViewById(R.id.player_error)
        retryButton = findViewById(R.id.player_retry)
        // docs/02 §8.2: explicit focus routing, no "no focus" dead zone. The root is the anchor while
        // the bar is down; UP/DOWN reach the only control while it is up; LEFT/RIGHT stay on it.
        infoAspect.nextFocusLeftId = infoAspect.id
        infoAspect.nextFocusRightId = infoAspect.id
        infoAspect.nextFocusUpId = infoAspect.id
        infoAspect.nextFocusDownId = infoAspect.id
        root.nextFocusDownId = root.id
        root.nextFocusUpId = root.id
        retryButton.nextFocusUpId = retryButton.id
    }

    private fun renderInfoBar(info: InfoBarState?) {
        infoName.text = info?.channelName ?: getString(R.string.player_channel_unknown)
        infoLogo.text = info?.channelName?.trim()?.take(1)?.ifEmpty { "?" } ?: "?"
        infoQuality.text = info?.qualityLabel ?: getString(R.string.player_quality_pending)
        // EPG now&next is P2-7; the slot is on screen from P1-4 so the layout is already proven.
        infoNowNext.text = info?.nowNext?.now?.title ?: getString(R.string.player_now_next_placeholder)
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
                // No failure card above the bar: UP ends on the bar itself, never on a gone view.
                infoAspect.nextFocusUpId = infoAspect.id
            }

            PlayerOverlay.Kind.STARTING, PlayerOverlay.Kind.BUFFERING -> {
                statusText.text = overlay.message
                statusGroup.visibility = View.VISIBLE
                errorGroup.visibility = View.GONE
                infoAspect.nextFocusUpId = infoAspect.id
            }

            PlayerOverlay.Kind.ERROR -> {
                statusGroup.visibility = View.GONE
                errorText.text = overlay.message
                errorGroup.visibility = View.VISIBLE
                // The failure card sits above the info bar, so the two controls must reach each
                // other: without this, pressing DOWN from "重试" strands the remote on "画幅" and the
                // retry entry point becomes unreachable (found on the device, docs/05 §16.6).
                retryButton.nextFocusDownId = infoAspect.id
                infoAspect.nextFocusUpId = retryButton.id
                // The retry entry point takes focus so one OK press is enough (§8.2: 失败要有重试入口).
                retryButton.post { retryButton.requestFocus() }
            }
        }
    }

    // ---------------------------------------------------------------- info bar lifecycle

    private fun showInfoBar() {
        visibility.show(now())
        backPolicy.onOverlayShown()
        root.nextFocusDownId = infoAspect.id
        if (infoBar.visibility != View.VISIBLE) {
            infoBar.animate().cancel()
            infoBar.alpha = 0f
            infoBar.visibility = View.VISIBLE
            infoBar.animate().alpha(1f).setDuration(InfoBarVisibility.INFO_BAR_FADE_MS).start()
        }
        mainHandler.removeCallbacks(hideRunnable)
        mainHandler.postDelayed(hideRunnable, InfoBarVisibility.INFO_BAR_TIMEOUT_MS + HIDE_POLL_MS)
    }

    private fun hideInfoBar() {
        visibility.hide()
        backPolicy.onOverlayHidden()
        mainHandler.removeCallbacks(hideRunnable)
        root.nextFocusDownId = root.id
        if (infoBar.hasFocus()) root.requestFocus()
        infoBar.animate().cancel()
        infoBar.animate()
            .alpha(0f)
            .setDuration(InfoBarVisibility.INFO_BAR_FADE_MS)
            .withEndAction { infoBar.visibility = View.GONE }
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

        /** One extra poll so `tick` sees a time strictly past `hideAtMs` (no same-millisecond race). */
        const val HIDE_POLL_MS = 16L
    }
}
