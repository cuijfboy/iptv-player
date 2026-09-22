package ilab.iptv.player.feature.epg.grid

import ilab.iptv.player.core.model.Programme

/**
 * What the programme-detail layer shows (work order item 4: 标题 / 时间 / 描述，有则显示).
 *
 * Pure, so the three cases a reviewer cares about are unit-tested rather than eyeballed: a programme with
 * a description, one without (`desc` is nullable in the schema, so the layer must say nothing instead of
 * printing "null"), and a cursor sitting in a gap where there is no programme at all.
 */
data class EpgDetail(
    val channelName: String,
    val title: String,
    /** `YYYY-MM-DDTHH:mm`-free, viewer-facing: date, then `HH:mm–HH:mm`, with a cross-day end marker. */
    val timeRange: String,
    val description: String?,
    /** 0..1 while the programme is on air, null otherwise — drives the progress bar. */
    val progress: Float?,
    val isLive: Boolean,
)

object EpgDetailPresenter {

    fun present(
        programme: Programme?,
        channelName: String,
        atMs: Long,
        timeAxis: TimeAxis,
        noProgrammeTitle: String = "该时段无节目",
        crossDaySuffix: String = "次日",
    ): EpgDetail {
        if (programme == null) {
            return EpgDetail(
                channelName = channelName,
                title = noProgrammeTitle,
                timeRange = timeAxis.label(atMs),
                description = null,
                progress = null,
                isLive = false,
            )
        }

        val startLabel = "${timeAxis.dayLabel(programme.startMs)} ${timeAxis.label(programme.startMs)}"
        val endLabel = if (isCrossDay(programme.startMs, programme.stopMs, timeAxis)) {
            "${timeAxis.label(programme.stopMs)}$crossDaySuffix"
        } else {
            timeAxis.label(programme.stopMs)
        }
        val live = atMs >= programme.startMs && atMs < programme.stopMs
        val progress = if (live && programme.stopMs > programme.startMs) {
            ((atMs - programme.startMs).toDouble() / (programme.stopMs - programme.startMs)).toFloat()
                .coerceIn(0f, 1f)
        } else {
            null
        }

        return EpgDetail(
            channelName = channelName,
            title = programme.title,
            timeRange = "$startLabel–$endLabel",
            description = programme.desc?.trim()?.takeIf { it.isNotEmpty() },
            progress = progress,
            isLive = live,
        )
    }

    /** True when the two instants fall on different civil days in the ruler's zone. */
    private fun isCrossDay(startMs: Long, stopMs: Long, timeAxis: TimeAxis): Boolean =
        timeAxis.dayLabel(startMs) != timeAxis.dayLabel(stopMs)
}
