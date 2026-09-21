package ilab.iptv.player.feature.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.NowNext
import ilab.iptv.player.core.model.Programme
import org.junit.Test

/** The info bar's one programme line (P2-7 item 4): which of now/next it shows, and when it shows nothing. */
class NowNextLabelTest {

    private fun programme(title: String) = Programme(
        id = 0,
        epgChannelId = "c1",
        startMs = 0,
        stopMs = 1,
        title = title,
        desc = null,
        category = null,
    )

    @Test
    fun `now wins while something is on air`() {
        val line = NowNextLabel.of(NowNext(now = programme("新闻联播"), next = programme("焦点访谈")))
        assertThat(line.kind).isEqualTo(NowNextLabel.Kind.NOW)
        assertThat(line.title).isEqualTo("新闻联播")
    }

    @Test
    fun `next is shown when only the following programme is known`() {
        val line = NowNextLabel.of(NowNext(now = null, next = programme("焦点访谈")))
        assertThat(line.kind).isEqualTo(NowNextLabel.Kind.NEXT)
        assertThat(line.title).isEqualTo("焦点访谈")
    }

    @Test
    fun `a channel with no epg shows nothing rather than a placeholder`() {
        assertThat(NowNextLabel.of(null).kind).isEqualTo(NowNextLabel.Kind.NONE)
        assertThat(NowNextLabel.of(NowNext(now = null, next = null)).kind)
            .isEqualTo(NowNextLabel.Kind.NONE)
    }

    @Test
    fun `a blank title is treated as no data`() {
        val line = NowNextLabel.of(NowNext(now = programme("   "), next = programme("焦点访谈")))
        assertThat(line.kind).isEqualTo(NowNextLabel.Kind.NEXT)
    }
}
