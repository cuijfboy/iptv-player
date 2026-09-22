package ilab.iptv.player.feature.epg

import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import ilab.iptv.player.feature.epg.grid.DrawSurface
import ilab.iptv.player.feature.epg.grid.GridPalette
import ilab.iptv.player.feature.epg.grid.GridTextStyle
import ilab.iptv.player.feature.epg.grid.TextHandle
import ilab.iptv.player.feature.epg.grid.TextKey
import ilab.iptv.player.feature.epg.grid.TextLayoutFactory
import kotlin.math.max

/**
 * The Android half of the grid's draw seam: the pure renderer talks to [DrawSurface], and this is the
 * only place a `Canvas` and a `Paint` exist. Keeping the seam this narrow is what lets the frame path be
 * benchmarked on the JVM (see `EpgGridBenchmarkTest`) without a device.
 */
class CanvasDrawSurface(private val canvas: Canvas) : DrawSurface {

    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val strokePaint = Paint().apply { style = Paint.Style.STROKE }

    override val widthPx: Int get() = canvas.width
    override val heightPx: Int get() = canvas.height

    override fun fillRect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        fillPaint.color = color
        canvas.drawRect(left, top, right, bottom, fillPaint)
    }

    override fun strokeRect(left: Float, top: Float, right: Float, bottom: Float, color: Int, strokeWidthPx: Float) {
        strokePaint.color = color
        strokePaint.strokeWidth = strokeWidthPx
        canvas.drawRect(left, top, right, bottom, strokePaint)
    }

    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, strokeWidthPx: Float) {
        strokePaint.color = color
        strokePaint.strokeWidth = strokeWidthPx
        canvas.drawLine(x0, y0, x1, y1, strokePaint)
    }

    override fun drawText(handle: TextHandle, x: Float, y: Float) {
        val android = handle as? AndroidTextHandle ?: return
        canvas.save()
        canvas.translate(x, y)
        android.layout.draw(canvas)
        canvas.restore()
    }
}

/** A `StaticLayout` plus the key it was built for — what [ilab.iptv.player.feature.epg.grid.TextLayoutStore] caches. */
class AndroidTextHandle(override val key: TextKey, val layout: StaticLayout) : TextHandle {
    override val heightPx: Float get() = layout.height.toFloat()
}

/**
 * Builds one single-line, ellipsised `StaticLayout` per miss (docs/02 §8.3: 结果按 (text,width,style) 缓存,
 * 上限 512). Sizes are the 10-foot floor of §8.2 (正文 ≥18sp is the *card* body; a grid block is a chart
 * label, so the block title is 15sp and the time line 12sp, both well above the legibility floor at
 * two metres on the 55" set this targets).
 */
class AndroidTextLayoutFactory(
    private val density: Float,
    private val palette: GridPalette = GridPalette(),
) : TextLayoutFactory {

    private val paints: Map<GridTextStyle, TextPaint> = mapOf(
        GridTextStyle.BLOCK_TITLE to paint(15f, palette.textPrimary),
        GridTextStyle.BLOCK_TIME to paint(12f, palette.textSecondary),
        GridTextStyle.RULER to paint(13f, palette.textSecondary),
        GridTextStyle.RULER_DAY to paint(15f, palette.textPrimary),
        GridTextStyle.CHANNEL to paint(15f, palette.textPrimary),
        GridTextStyle.PLACEHOLDER to paint(13f, palette.textMuted),
    )

    override fun create(key: TextKey): TextHandle {
        val paint = paints.getValue(key.style)
        val width = max(1, key.widthPx)
        // `StaticLayout.Builder` is API 23; minSdk is 21, so the pre-23 path uses the legacy constructor
        // that takes the same ellipsize. The target set is API 31, so the branch below is a compatibility
        // floor rather than a path in use — but it must exist for minSdk 21 to be honest.
        val layout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder
                .obtain(key.text, 0, key.text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(1)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
        } else {
            // The pre-23 path has no builder, so the text is ellipsised first and then laid out on one
            // line. Reached only on API 21–22; the target set is API 31.
            @Suppress("DEPRECATION")
            val fitted = TextUtils.ellipsize(key.text, paint, width.toFloat(), TextUtils.TruncateAt.END)
            @Suppress("DEPRECATION")
            StaticLayout(
                fitted,
                paint,
                width,
                Layout.Alignment.ALIGN_NORMAL,
                1f,
                0f,
                false,
            )
        }
        return AndroidTextHandle(key, layout)
    }

    private fun paint(sizeSp: Float, color: Int): TextPaint = TextPaint().apply {
        isAntiAlias = true
        textSize = sizeSp * density
        this.color = color
    }
}
