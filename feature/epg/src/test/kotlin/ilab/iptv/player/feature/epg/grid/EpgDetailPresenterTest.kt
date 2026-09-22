package ilab.iptv.player.feature.epg.grid

import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class EpgDetailPresenterTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val axis = TimeAxis(zone)
    private val at20: Long = Calendar.getInstance(zone).apply {
        clear()
        set(2026, 4, 15, 20, 0, 0)
    }.timeInMillis

    @Test
    fun `a programme with a description shows title, time and description`() {
        val programme = programme(1, "cctv1", at20, 90, "新闻联播", desc = "  每日新闻  ")
        val detail = EpgDetailPresenter.present(programme, "CCTV-1", at20 + 45 * MINUTE_MS, axis)
        assertThat(detail.title).isEqualTo("新闻联播")
        assertThat(detail.timeRange).isEqualTo("05-15 20:00–21:30")
        assertThat(detail.description).isEqualTo("每日新闻")
        assertThat(detail.isLive).isTrue()
        assertThat(detail.progress).isWithin(0.001f).of(0.5f)
    }

    @Test
    fun `a programme without a description says nothing rather than printing null`() {
        val noDesc = EpgDetailPresenter.present(programme(1, "c", at20, 60, "No desc"), "C", at20, axis)
        assertThat(noDesc.description).isNull()

        val blank = EpgDetailPresenter.present(programme(2, "c", at20, 60, "Blank", desc = "   "), "C", at20, axis)
        assertThat(blank.description).isNull()
    }

    @Test
    fun `a cursor in a gap reports no programme`() {
        val detail = EpgDetailPresenter.present(null, "CCTV-1", at20, axis)
        assertThat(detail.title).isEqualTo("该时段无节目")
        assertThat(detail.timeRange).isEqualTo("20:00")
        assertThat(detail.progress).isNull()
        assertThat(detail.isLive).isFalse()
    }

    @Test
    fun `a programme that is not on air has no progress bar`() {
        val programme = programme(1, "c", at20, 60, "Later")
        val before = EpgDetailPresenter.present(programme, "C", at20 - HOUR_MS, axis)
        assertThat(before.progress).isNull()
        assertThat(before.isLive).isFalse()
        val after = EpgDetailPresenter.present(programme, "C", at20 + 2 * HOUR_MS, axis)
        assertThat(after.progress).isNull()
    }

    @Test
    fun `a programme crossing midnight marks the end as the next day`() {
        val start = Calendar.getInstance(zone).apply {
            clear()
            set(2026, 4, 15, 23, 30, 0)
        }.timeInMillis
        val crossing = programme(9, "c", start, 60, "Late night")
        val detail = EpgDetailPresenter.present(crossing, "C", start, axis)
        assertThat(detail.timeRange).isEqualTo("05-15 23:30–00:30次日")
        assertThat(detail.isLive).isTrue()
    }
}
