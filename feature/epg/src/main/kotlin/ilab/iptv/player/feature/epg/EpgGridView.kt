package ilab.iptv.player.feature.epg

import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.widget.OverScroller
import ilab.iptv.player.feature.epg.grid.EpgGridRenderer
import ilab.iptv.player.feature.epg.grid.FocusNavigator
import ilab.iptv.player.feature.epg.grid.GridFrame
import ilab.iptv.player.feature.epg.grid.GridFrameBuilder
import ilab.iptv.player.feature.epg.grid.GridFrameInput
import ilab.iptv.player.feature.epg.grid.GridGeometry
import ilab.iptv.player.feature.epg.grid.GridMetrics
import ilab.iptv.player.feature.epg.grid.GridPalette
import ilab.iptv.player.feature.epg.grid.GridPerfAccumulator
import ilab.iptv.player.feature.epg.grid.GridPerfSummary
import ilab.iptv.player.feature.epg.grid.GridRowInput
import ilab.iptv.player.feature.epg.grid.GridSelection
import ilab.iptv.player.feature.epg.grid.GridStrings
import ilab.iptv.player.feature.epg.grid.ScrollOffset
import ilab.iptv.player.feature.epg.grid.TextLayoutStore
import ilab.iptv.player.feature.epg.grid.TimeAxis
import ilab.iptv.player.feature.epg.grid.programmesToRowInput

/**
 * The self-drawn EPG time grid (docs/02 §8.3). It owns exactly three things:
 *
 * 1. the **scroll + cursor** state (plus the `OverScroller` that animates a page scroll);
 * 2. one call per draw into [GridFrameBuilder] and [EpgGridRenderer] — the visible rectangle, never the
 *    whole guide;
 * 3. the remote's keys, delegated to the pure [FocusNavigator].
 *
 * All the arithmetic that can be wrong lives in the pure package; this class is the Android adapter.
 */
class EpgGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** What the grid tells the screen. Channel ids, not classes: the screen owns navigation. */
    interface Listener {
        fun onSelectionChanged(rowIndex: Int, timeMs: Long, channelId: Long?)

        /** The cursor moved: the screen extends the query window when it nears an edge. */
        fun onCursorMoved(timeMs: Long)

        /** OK on a block: the screen shows the detail layer (work order item 4). */
        fun onProgrammeActivated(rowIndex: Int, channelId: Long, channelName: String, programmeId: Long?, timeMs: Long)

        fun onVisibleRowsChanged(firstRow: Int, lastRow: Int)
    }

    var listener: Listener? = null

    private val metrics = GridMetrics.fromDensity(resources.displayMetrics.density)
    private val palette = GridPalette()
    private val timeAxis = TimeAxis()
    private val strings = GridStrings(
        noEpg = context.getString(R.string.epg_placeholder_no_data),
        loading = context.getString(R.string.epg_placeholder_loading),
        nowLabel = context.getString(R.string.epg_now),
    )
    private val store = TextLayoutStore(
        AndroidTextLayoutFactory(resources.displayMetrics.density, palette),
    )
    private val renderer = EpgGridRenderer(timeAxis, palette, strings)
    private val frameBuilder = GridFrameBuilder(timeAxis)
    private val navigator = FocusNavigator(timeAxis)
    private val scroller = OverScroller(context)
    private val accumulator = GridPerfAccumulator()

    private var state: EpgGridUiState? = null
    private var rows: List<GridRowInput> = emptyList()
    private var scroll = ScrollOffset.ZERO
    private var selection: GridSelection? = null
    private var lastFrame: GridFrame? = null
    private var lastNotifiedRows: IntRange? = null

    private var sampling = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!sampling) return
            accumulator.onFrame(frameTimeNanos, lastFrame?.drawnBlocks ?: 0)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultFocusHighlightEnabled = false
        }
        setBackgroundColor(palette.background)
    }

    /** Publishes a new state. Row identity is index-based, so a re-load keeps the cursor where it was. */
    fun submit(state: EpgGridUiState) {
        this.state = state
        rows = state.channels.map { channel ->
            val programmes = if (channel.id in state.loadedChannelIds) {
                state.programmesByChannel[channel.id] ?: emptyList()
            } else {
                null
            }
            programmesToRowInput(
                channelId = channel.id,
                name = channel.name,
                channelNo = channel.channelNo,
                programmes = programmes,
                hasBinding = channel.epgChannelId != null,
            )
        }
        val current = selection
        selection = when {
            rows.isEmpty() -> null
            current == null || current.rowIndex >= rows.size -> navigator.initial(0, state.window, rows.first(), state.nowMs)
            else -> GridSelection.of(current.rowIndex, current.timeMs, rows[current.rowIndex])
        }
        invalidate()
    }

    /** Puts the cursor on a channel and brings it into view — the browse screen's "show me this one". */
    fun selectChannel(channelId: Long, nowMs: Long) {
        val rowIndex = rows.indexOfFirst { it.channelId == channelId }
        val window = state?.window ?: return
        val row = if (rowIndex >= 0) rowIndex else 0
        val target = navigator.initial(row, window, rows.getOrNull(row), nowMs)
        selection = target
        scroll = navigator.ensureVisible(target, metrics, width, height, scroll, window, rows.size)
        listener?.onSelectionChanged(target.rowIndex, target.timeMs, rows.getOrNull(target.rowIndex)?.channelId)
        invalidate()
    }

    fun beginPerfSampling() {
        accumulator.reset()
        store.resetCounters()
        sampling = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Stops sampling and answers the window's numbers, or null when no frame was drawn. */
    fun endPerfSampling(): GridPerfSummary? {
        sampling = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        val frame = lastFrame ?: return null
        return accumulator.summary(store, frame.virtualizationSkipRatio)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = state ?: return
        if (width == 0 || height == 0 || rows.isEmpty()) {
            canvas.drawColor(palette.background)
            return
        }
        val input = GridFrameInput(
            metrics = metrics,
            viewportWidthPx = width,
            viewportHeightPx = height,
            contentWindow = state.window,
            scroll = scroll,
            rows = rows,
            selection = selection,
            nowMs = state.nowMs,
        )
        val frame = frameBuilder.build(input)
        renderer.render(CanvasDrawSurface(canvas), store, frame)
        // Adopt the clamped scroll so the next key press starts from what is actually on screen.
        scroll = frame.geometry.scroll
        lastFrame = frame
        notifyVisibleRows(frame)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = clamped(ScrollOffset(scroller.currX, scroller.currY))
            postInvalidateOnAnimation()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val current = selection ?: return super.onKeyDown(keyCode, event)
        val state = state ?: return super.onKeyDown(keyCode, event)
        if (rows.isEmpty()) return super.onKeyDown(keyCode, event)

        val next = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> navigator.moveLeft(current, rows.size, state.window, ::rowAt)
            KeyEvent.KEYCODE_DPAD_RIGHT -> navigator.moveRight(current, rows.size, state.window, ::rowAt)
            KeyEvent.KEYCODE_DPAD_UP -> navigator.moveUp(current, rows.size, ::rowAt)
            KeyEvent.KEYCODE_DPAD_DOWN -> navigator.moveDown(current, rows.size, ::rowAt)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val row = rows.getOrNull(current.rowIndex)
                if (row != null) {
                    listener?.onProgrammeActivated(
                        rowIndex = current.rowIndex,
                        channelId = row.channelId,
                        channelName = row.name,
                        programmeId = current.programmeId,
                        timeMs = current.timeMs,
                    )
                }
                return true
            }

            else -> return super.onKeyDown(keyCode, event)
        }

        selection = next
        val desired = navigator.ensureVisible(next, metrics, width, height, scroll, state.window, rows.size)
        if (desired != scroll) {
            // A page turn over ~0.2 s: the §8.3 OverScroller, used for exactly this one motion.
            scroller.startScroll(scroll.xPx, scroll.yPx, desired.xPx - scroll.xPx, desired.yPx - scroll.yPx, PAGE_ANIM_MS)
            postInvalidateOnAnimation()
        }
        listener?.onSelectionChanged(next.rowIndex, next.timeMs, rows.getOrNull(next.rowIndex)?.channelId)
        listener?.onCursorMoved(next.timeMs)
        invalidate()
        return true
    }

    private fun rowAt(index: Int): GridRowInput? = rows.getOrNull(index)

    private fun clamped(offset: ScrollOffset): ScrollOffset {
        val state = state ?: return offset
        return GridGeometry(metrics, width, height, state.window, rows.size, offset).scroll
    }

    private fun notifyVisibleRows(frame: GridFrame) {
        if (frame.rows.isEmpty()) return
        val range = frame.rows.first().rowIndex..frame.rows.last().rowIndex
        if (range == lastNotifiedRows) return
        lastNotifiedRows = range
        post { listener?.onVisibleRowsChanged(range.first, range.last) }
    }

    private companion object {
        const val PAGE_ANIM_MS = 200
    }
}
