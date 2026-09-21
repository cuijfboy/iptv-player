package ilab.iptv.spike

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.widget.OverScroller
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * S3 fixture: the throwaway Canvas EPG-grid prototype (docs/02 §8.3 contract, docs/04 §2.2 S3).
 *
 * Draws `channels` rows x `hours` of a time grid with 2D virtualisation (only the visible rectangle
 * is drawn), scrolls it with OverScroller flings and samples frame intervals with Choreographer.
 *
 * adb:
 *   am start -n ilab.iptv.player.spike/.EpgGridSpikeActivity \
 *      -e mode virtual|naive -e channels 658 -e hours 6 -e durationMs 30000 -e tag s3a
 */
class EpgGridSpikeActivity : Activity() {

    private lateinit var grid: EpgGrid
    private val frameTimes = ArrayList<Long>(2048)
    private var sampling = false
    private var startedAt = 0L
    private var durationMs = 30_000L
    private var tag = "s3"
    private var mode = "virtual"
    private var channels = 658
    private var hours = 6
    private var pssBeforeKb = 0
    private var drawnBlocksTotal = 0
    private var framesWithBlocks = 0
    // NB: cannot be a field initialiser — Activity constructors run before the base context exists.
    private lateinit var scroller: OverScroller
    private var direction = 1
    private var directionY = 1
    private var lastFrameNanos = 0L
    private var lastLogAt = 0L

    private val choreographer = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!sampling) return
            if (SystemClock.uptimeMillis() - lastLogAt >= 5_000) {
                Log.i(SpikeIo.TAG, "S3_PROGRESS tag=$tag mode=$mode drawCalls=${grid.drawCallCount} " +
                    "blocksDrawn=${grid.drawnBlocks} scroll=(${grid.offsetX},${grid.offsetY}) " +
                    "viewport=${grid.width}x${grid.height}")
                lastLogAt = SystemClock.uptimeMillis()
            }
            if (lastFrameNanos != 0L) frameTimes.add(frameTimeNanos - lastFrameNanos)
            lastFrameNanos = frameTimeNanos
            if (scroller.computeScrollOffset()) {
                grid.offsetX = scroller.currX
                grid.offsetY = scroller.currY
                grid.invalidate()
            } else {
                flingAgain()
            }
            if (SystemClock.uptimeMillis() - startedAt >= durationMs) {
                finishSampling()
            } else {
                choreographer.postFrameCallback(this)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = intent.getStringExtra("mode") ?: "virtual"
        tag = intent.getStringExtra("tag") ?: mode
        channels = (intent.getStringExtra("channels") ?: "658").toInt()
        hours = (intent.getStringExtra("hours") ?: "6").toInt()
        durationMs = (intent.getStringExtra("durationMs") ?: "30000").toLong()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scroller = OverScroller(this)

        grid = EpgGrid(this, channels, hours, naive = mode == "naive")
        setContentView(grid)
        grid.post {
            // warm up, then sample: first frames include layout + font loading
            grid.postDelayed({
                pssBeforeKb = pss()
                startSampling()
            }, 3_000)
        }
    }

    private fun startSampling() {
        drawnBlocksTotal = 0
        framesWithBlocks = 0
        frameTimes.clear()
        lastFrameNanos = 0L
        startedAt = SystemClock.uptimeMillis()
        lastLogAt = startedAt
        sampling = true
        flingAgain()
        choreographer.postFrameCallback(frameCallback)
        Log.i(SpikeIo.TAG, "S3_START mode=$mode tag=$tag channels=$channels hours=$hours " +
            "durationMs=$durationMs viewport=${grid.width}x${grid.height}")
    }

    private fun flingAgain() {
        val maxX = max(0, grid.gridWidth.toInt() - grid.width)
        val maxY = max(0, grid.gridHeight.toInt() - grid.height)
        scroller.fling(grid.offsetX, grid.offsetY, 700 * direction, 3_500 * directionY,
            0, maxX, 0, maxY)
        if (direction > 0 && grid.offsetX >= maxX - 8) direction = -1
        if (direction < 0 && grid.offsetX <= 8) direction = 1
        if (directionY > 0 && grid.offsetY >= maxY - 8) directionY = -1
        if (directionY < 0 && grid.offsetY <= 8) directionY = 1
    }

    private fun finishSampling() {
        sampling = false
        val pssAfterKb = pss()
        val sorted = frameTimes.sorted()
        fun pct(p: Double): Double =
            if (sorted.isEmpty()) 0.0 else sorted[min(sorted.size - 1, (sorted.size * p).toInt())] / 1_000_000.0
        val avgMs = if (sorted.isEmpty()) 0.0 else sorted.average() / 1_000_000.0
        val summary = JSONObject()
            .put("mode", mode)
            .put("tag", tag)
            .put("channels", channels)
            .put("hours", hours)
            .put("durationMs", durationMs)
            .put("frames", sorted.size)
            .put("frameIntervalAvgMs", round2(avgMs))
            .put("frameIntervalP50Ms", round2(pct(0.50)))
            .put("frameIntervalP90Ms", round2(pct(0.90)))
            .put("frameIntervalP95Ms", round2(pct(0.95)))
            .put("frameIntervalP99Ms", round2(pct(0.99)))
            .put("frameIntervalMaxMs", round2(if (sorted.isEmpty()) 0.0 else sorted.last() / 1_000_000.0))
            .put("avgFpsFromP50", if (pct(0.50) > 0) round2(1000.0 / pct(0.50)) else JSONObject.NULL)
            .put("passesP95Under16_7Ms", pct(0.95) > 0 && pct(0.95) <= 16.7)
            .put("drawCalls", grid.drawCallCount)
            .put("drawnBlocksTotal", grid.drawnBlocks)
            .put("drawnBlocksPerDrawCall",
                if (grid.drawCallCount > 0) grid.drawnBlocks / grid.drawCallCount else 0)
            .put("scrollOffsetXMin", grid.offsetXMin)
            .put("scrollOffsetXMax", grid.offsetXMax)
            .put("scrollOffsetYMin", grid.offsetYMin)
            .put("scrollOffsetYMax", grid.offsetYMax)
            .put("viewportPx", "${grid.width}x${grid.height}")
            .put("gridPx", "${grid.gridWidth}x${grid.gridHeight}")
            .put("density", resources.displayMetrics.density)
            .put("pssBeforeKb", pssBeforeKb)
            .put("pssAfterKb", pssAfterKb)
            .put("pssDeltaKb", pssAfterKb - pssBeforeKb)
            .put("textLayoutCacheSize", grid.textLayoutCacheSize())
        SpikeIo.write(this, "s3_${tag}.json", summary.toString(1))
        Log.i(SpikeIo.TAG, "S3_DONE mode=$mode tag=$tag frames=${sorted.size} " +
            "avgMs=${round2(avgMs)} p50=${round2(pct(0.50))} p95=${round2(pct(0.95))} " +
            "p99=${round2(pct(0.99))} max=${round2(if (sorted.isEmpty()) 0.0 else sorted.last() / 1_000_000.0)} " +
            "pssDeltaKb=${pssAfterKb - pssBeforeKb}")
        grid.postDelayed({ finish() }, 500)
    }

    private fun pss(): Int {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        return info.totalPss
    }

    private fun round2(value: Double): Double = Math.round(value * 100.0) / 100.0
}

/** Minimal throwaway EPG grid: time axis horizontally, channel rows vertically. */
class EpgGrid(
    context: Context,
    private val channelCount: Int,
    private val hours: Int,
    private val naive: Boolean,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val rowH = 40f * density
    private val pxPerMinute = 3.2f * density
    private val channelColumnW = 140f * density
    private val headerH = 34f * density
    private val blockGap = 1.5f * density

    private val bgPaint = Paint().apply { color = Color.parseColor("#12161C") }
    private val rowPaint = Paint().apply { color = Color.parseColor("#1B2027") }
    private val channelColPaint = Paint().apply { color = Color.parseColor("#0C0F13") }
    private val headerPaint = Paint().apply { color = Color.parseColor("#0C0F13") }
    private val gridPaint = Paint().apply {
        color = Color.parseColor("#2A313B")
        strokeWidth = 1f
    }
    private val blockPaint = Paint().apply { color = Color.parseColor("#2E4A6B") }
    private val blockPaintAlt = Paint().apply { color = Color.parseColor("#3B6B4A") }
    private val nowPaint = Paint().apply {
        color = Color.parseColor("#D64545")
        strokeWidth = 2f
    }
    private val labelPaint = TextPaint().apply {
        color = Color.WHITE
        textSize = 15f * density
        isAntiAlias = true
    }
    private val cache = HashMap<String, StaticLayout>()

    private var offsetXValue = 0
    var offsetX: Int
        get() = offsetXValue
        set(value) {
            offsetXValue = max(0, min(value, max(0, gridWidth.toInt() - width)))
        }

    private var offsetYValue = 0
    var offsetY: Int
        get() = offsetYValue
        set(value) {
            offsetYValue = max(0, min(value, max(0, gridHeight.toInt() - height)))
        }

    /** Instrumentation: proof that the grid really drew, and how much per draw call. */
    var drawCallCount = 0
        private set
    var drawnBlocks = 0L
        private set
    var offsetXMin = Int.MAX_VALUE
        private set
    var offsetXMax = 0
        private set
    var offsetYMin = Int.MAX_VALUE
        private set
    var offsetYMax = 0
        private set

    val gridWidth: Float get() = channelColumnW + hours * 60f * pxPerMinute
    val gridHeight: Float get() = headerH + channelCount * rowH
    fun textLayoutCacheSize(): Int = cache.size

    override fun onDraw(canvas: Canvas) {
        var drawn = 0
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.save()
        canvas.translate(-offsetXValue.toFloat(), -offsetYValue.toFloat())
        val firstRow = if (naive) 0 else
            max(0, ((offsetYValue - headerH) / rowH).toInt())
        val lastRow = if (naive) channelCount - 1 else
            min(channelCount - 1, ((offsetYValue + height) / rowH).toInt())
        for (r in firstRow..lastRow) {
            val top = headerH + r * rowH
            canvas.drawRect(0f, top, gridWidth, top + rowH, rowPaint)
            canvas.drawLine(0f, top, gridWidth, top, gridPaint)
            var minute = (r % 4) * 7.5f
            var index = 0
            while (minute < hours * 60f) {
                val blockMinutes = if ((r + index) % 3 == 0) 60f else 30f
                val left = channelColumnW + minute * pxPerMinute
                val right = left + blockMinutes * pxPerMinute - blockGap
                if (naive || (right > offsetXValue && left < offsetXValue + width)) {
                    canvas.drawRect(left, top + blockGap, right, top + rowH - blockGap,
                        if (index % 2 == 0) blockPaint else blockPaintAlt)
                    val text = "CH${r + 1} P${index + 1}"
                    val layout = layoutFor(text, (right - left).toInt())
                    canvas.save()
                    canvas.translate(left + 6f * density, top + 4f * density)
                    layout.draw(canvas)
                    canvas.restore()
                    drawn++
                }
                minute += blockMinutes
                index++
            }
        }
        val nowX = channelColumnW + 60f * pxPerMinute
        canvas.drawLine(nowX, headerH, nowX, headerH + channelCount * rowH, nowPaint)
        canvas.restore()
        drawCallCount++
        drawnBlocks += drawn
        offsetXMin = min(offsetXMin, offsetXValue)
        offsetXMax = max(offsetXMax, offsetXValue)
        offsetYMin = min(offsetYMin, offsetYValue)
        offsetYMax = max(offsetYMax, offsetYValue)

        // fixed chrome: header ruler (scrolls horizontally) + channel column (scrolls vertically)
        canvas.save()
        canvas.translate(-offsetXValue.toFloat(), 0f)
        canvas.drawRect(offsetXValue.toFloat(), 0f, (offsetXValue + width).toFloat(), headerH,
            headerPaint)
        for (m in 0..(hours * 60) step 30) {
            val x = channelColumnW + m * pxPerMinute
            canvas.drawLine(x, 0f, x, headerH, gridPaint)
            canvas.drawText("${m / 60}:${(m % 60).toString().padStart(2, '0')}",
                x + 4f * density, headerH - 10f * density, labelPaint)
        }
        canvas.restore()
        canvas.save()
        canvas.translate(0f, -offsetYValue.toFloat())
        canvas.drawRect(0f, offsetYValue.toFloat(), channelColumnW, (offsetYValue + height).toFloat(),
            channelColPaint)
        for (r in firstRow..lastRow) {
            val top = headerH + r * rowH
            canvas.drawText("Channel ${r + 1}", 8f * density, top + 26f * density, labelPaint)
        }
        canvas.restore()
    }

    private fun layoutFor(text: String, widthPx: Int): StaticLayout {
        val key = "$text|$widthPx"
        cache[key]?.let { return it }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, labelPaint, max(1, widthPx - 8))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(1)
            .setIncludePad(false)
            .build()
        if (cache.size < 512) cache[key] = layout
        return layout
    }
}
