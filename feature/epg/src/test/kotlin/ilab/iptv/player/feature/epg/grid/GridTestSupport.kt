package ilab.iptv.player.feature.epg.grid

import ilab.iptv.player.core.model.Programme

/** Shared fixtures for the grid tests — and for the offline benchmark, which is a test for the same reason. */

const val MINUTE_MS: Long = 60_000L
const val HOUR_MS: Long = 60L * MINUTE_MS

fun programme(
    id: Long,
    channelKey: String,
    startMs: Long,
    durationMinutes: Long,
    title: String,
    desc: String? = null,
): Programme = Programme(
    id = id,
    epgChannelId = channelKey,
    startMs = startMs,
    stopMs = startMs + durationMinutes * MINUTE_MS,
    title = title,
    desc = desc,
    category = null,
)

/** A guide of [hours] with the prototype's 30/60-minute mix, so block counts stay comparable to S3. */
fun guideFor(
    channelIndex: Int,
    startMs: Long,
    hours: Int,
    channelKey: String,
    idBase: Long,
): List<Programme> {
    val programmes = ArrayList<Programme>(hours * 2)
    var minute = (channelIndex % 4) * 7.5
    var index = 0
    var id = idBase
    val endMinute = hours * 60.0
    while (minute < endMinute) {
        val durationMinutes = if ((channelIndex + index) % 3 == 0) 60L else 30L
        programmes += programme(
            id = id++,
            channelKey = channelKey,
            startMs = startMs + (minute * MINUTE_MS).toLong(),
            durationMinutes = durationMinutes,
            title = "CH${channelIndex + 1} P${index + 1}",
        )
        minute += durationMinutes
        index++
    }
    return programmes
}

/** One loaded row per channel, the shape the grid gets from a fully answered page. */
fun loadedRows(channelCount: Int, startMs: Long, hours: Int): List<GridRowInput> =
    List(channelCount) { index ->
        val key = "ch-$index"
        GridRowInput(
            channelId = (index + 1).toLong(),
            name = "Channel ${index + 1}",
            channelNo = index + 1,
            state = EpgRowState.LOADED,
            index = ProgrammeIndex.of(guideFor(index, startMs, hours, key, (index + 1) * 1000L)),
        )
    }

/** Stub text handle: no layout engine, just enough shape for the renderer. */
class StubTextHandle(override val key: TextKey, override val heightPx: Float = 18f) : TextHandle

class StubTextFactory : TextLayoutFactory {
    var created: Int = 0
        private set

    override fun create(key: TextKey): TextHandle {
        created++
        return StubTextHandle(key)
    }
}

/** Records what a frame asked the surface to draw — the ground truth behind the benchmark's numbers. */
class RecordingSurface(
    override val widthPx: Int,
    override val heightPx: Int,
) : DrawSurface {

    var fills: Int = 0
        private set
    var strokes: Int = 0
        private set
    var lines: Int = 0
        private set
    var texts: Int = 0
        private set
    var textsOutsideViewport: Int = 0
        private set

    override fun fillRect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        fills++
    }

    override fun strokeRect(left: Float, top: Float, right: Float, bottom: Float, color: Int, strokeWidthPx: Float) {
        strokes++
    }

    override fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, color: Int, strokeWidthPx: Float) {
        lines++
    }

    override fun drawText(handle: TextHandle, x: Float, y: Float) {
        texts++
        if (x < -1f || y < -1f || x > widthPx + 1f || y > heightPx + 1f) textsOutsideViewport++
    }
}

/** A 1920×1080 TV at density 2.0 — the viewport the benchmark's block counts are quoted for. */
const val TV_DENSITY: Float = 2f
const val TV_WIDTH_PX: Int = 1920
const val TV_HEIGHT_PX: Int = 1080

fun tvMetrics(): GridMetrics = GridMetrics.fromDensity(TV_DENSITY)
