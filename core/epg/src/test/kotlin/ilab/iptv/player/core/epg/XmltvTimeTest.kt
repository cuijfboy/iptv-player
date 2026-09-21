package ilab.iptv.player.core.epg

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * XMLTV timestamps, including the two cases the job calls out: time zones and DST boundaries.
 *
 * The DST pair below is the whole reason the offset is parsed instead of assumed. In Berlin the civil
 * time 02:00 exists twice on the fall-back night (CEST +0200, then CET +0100); the guide disambiguates
 * it with the offset, and the parser must keep the two instants one hour apart.
 */
class XmltvTimeTest {

    @Test
    fun `parses an explicit offset`() {
        val value = XmltvTime.parse("20260921183000 +0800")
        assertThat(value).isEqualTo(1_789_986_600_000L)
    }

    @Test
    fun `accepts Z, colon offsets and a missing seconds field`() {
        assertThat(XmltvTime.parse("20260921103000Z")).isEqualTo(1_789_986_600_000L)
        assertThat(XmltvTime.parse("20260921183000+08:00")).isEqualTo(1_789_986_600_000L)
        assertThat(XmltvTime.parse("202609211830 +0800")).isEqualTo(1_789_986_600_000L)
    }

    @Test
    fun `a missing offset is read as UTC`() {
        assertThat(XmltvTime.parse("20260921103000")).isEqualTo(1_789_986_600_000L)
    }

    @Test
    fun `the DST fold keeps two different instants one hour apart`() {
        val summer = XmltvTime.parse("20261025020000 +0200")
        val winter = XmltvTime.parse("20261025020000 +0100")
        assertThat(summer).isNotNull()
        assertThat(winter).isNotNull()
        assertThat(winter!! - summer!!).isEqualTo(3_600_000L)
    }

    @Test
    fun `negative offsets are applied in the other direction`() {
        val value = XmltvTime.parse("20260921183000 -0500")
        assertThat(value).isEqualTo(1_789_986_600_000L + 13 * 3_600_000L)
    }

    @Test
    fun `invalid values answer null instead of a guessed time`() {
        assertThat(XmltvTime.parse(null)).isNull()
        assertThat(XmltvTime.parse("")).isNull()
        assertThat(XmltvTime.parse("not-a-date")).isNull()
        assertThat(XmltvTime.parse("20261321103000 +0800")).isNull()
        assertThat(XmltvTime.parse("20260921253000 +0800")).isNull()
        assertThat(XmltvTime.parse("20260231103000 +0800")).isNull()
        assertThat(XmltvTime.parse("19000101103000 +0800")).isNull()
        assertThat(XmltvTime.parse("20260921103000 +9900")).isNull()
        assertThat(XmltvTime.parse("20260921103000 Q+0800")).isNull()
    }

    @Test
    fun `normalizes to UTC regardless of which side of midnight it lands on`() {
        val afterMidnightBeijing = XmltvTime.parse("20260922003000 +0800")
        val beforeMidnightUtc = XmltvTime.parse("20260921163000 Z")
        assertThat(afterMidnightBeijing).isEqualTo(beforeMidnightUtc)
    }
}
