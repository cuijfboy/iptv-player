package ilab.iptv.player.core.epg

import com.google.common.truth.Truth.assertThat
import java.io.StringReader
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The streaming parser against the shapes real XMLTV files have: a declaration, comments, `<channel>`
 * elements with several display names, `<programme>` elements with `title`/`desc`/`category`, entities,
 * CDATA, self-closing elements, and the malformed rows that must be skipped rather than crash a refresh.
 */
class XmltvPullParserTest {

    private val parser = XmltvPullParser()

    private class Collected {
        val channels = mutableListOf<XmltvChannel>()
        val programmes = mutableListOf<XmltvProgramme>()
    }

    private fun parse(xml: String): Pair<Collected, XmltvParseResult> {
        val collected = Collected()
        val result = runBlocking {
            parser.parse(
                input = StringReader(xml),
                onChannel = { collected.channels += it },
                onProgramme = { collected.programmes += it },
            )
        }
        return collected to result
    }

    @Test
    fun `reads channels and programmes, resolving local times to epoch millis`() {
        val (collected, result) = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tv SYSTEM "xmltv.dtd">
            <tv generator-info-name="test">
              <!-- a comment between elements -->
              <channel id="CCTV1.cn">
                <display-name>CCTV-1</display-name>
                <display-name lang="zh">CCTV-1 综合</display-name>
                <icon src="http://example.invalid/logo.png"/>
              </channel>
              <programme start="20260921183000 +0800" stop="20260921190000 +0800" channel="CCTV1.cn">
                <title lang="zh">新闻联播</title>
                <desc lang="zh">晚间的新闻节目</desc>
                <category>新闻</category>
              </programme>
            </tv>
            """.trimIndent(),
        )

        assertThat(result.channels).isEqualTo(1)
        assertThat(result.programmes).isEqualTo(1)
        assertThat(result.skipped).isEqualTo(0)
        assertThat(result.malformed).isFalse()

        assertThat(collected.channels.single().id).isEqualTo("CCTV1.cn")
        assertThat(collected.channels.single().displayNames)
            .containsExactly("CCTV-1", "CCTV-1 综合")

        val programme = collected.programmes.single()
        assertThat(programme.epgChannelId).isEqualTo("CCTV1.cn")
        assertThat(programme.startMs).isEqualTo(1_789_986_600_000L)
        assertThat(programme.stopMs).isEqualTo(1_789_986_600_000L + 30 * 60 * 1000L)
        assertThat(programme.title).isEqualTo("新闻联播")
        assertThat(programme.desc).isEqualTo("晚间的新闻节目")
        assertThat(programme.category).isEqualTo("新闻")
    }

    @Test
    fun `decodes predefined entities and numeric references`() {
        val (collected, _) = parse(
            """
            <tv>
              <programme start="20260921183000 +0800" stop="20260921190000 +0800" channel="c1">
                <title>A &amp; B &lt;live&gt; &#26032;&#x95fb;</title>
                <desc><![CDATA[raw <not-a-tag> text]]></desc>
              </programme>
            </tv>
            """.trimIndent(),
        )
        val programme = collected.programmes.single()
        assertThat(programme.title).isEqualTo("A & B <live> 新闻")
        assertThat(programme.desc).isEqualTo("raw <not-a-tag> text")
    }

    @Test
    fun `ignores unknown elements and nested attributes without losing the row`() {
        val (collected, _) = parse(
            """
            <tv>
              <programme start="20260921183000 +0800" stop="20260921190000 +0800" channel="c1">
                <title>Title</title>
                <credits><director>Someone</director></credits>
                <rating system="MPAA"><value>PG</value></rating>
                <icon src="http://example.invalid/p.png"/>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertThat(collected.programmes.single().title).isEqualTo("Title")
        assertThat(collected.programmes.single().desc).isNull()
    }

    @Test
    fun `a programme without a stop gets the default duration and is not counted as skipped`() {
        val (collected, result) = parse(
            """
            <tv>
              <programme start="20260921183000 +0800" channel="c1"><title>No stop</title></programme>
            </tv>
            """.trimIndent(),
        )
        val programme = collected.programmes.single()
        assertThat(programme.stopMs - programme.startMs).isEqualTo(XmltvTime.DEFAULT_DURATION_MS)
        assertThat(result.skipped).isEqualTo(0)
    }

    @Test
    fun `rows missing a channel, a start or a title are skipped, not guessed`() {
        val (collected, result) = parse(
            """
            <tv>
              <programme start="20260921183000 +0800" stop="20260921190000 +0800" channel="c1">
                <title>good</title>
              </programme>
              <programme start="bogus" stop="20260921190000 +0800" channel="c1"><title>bad start</title></programme>
              <programme start="20260921183000 +0800" stop="20260921190000 +0800"><title>no channel</title></programme>
              <programme start="20260921183000 +0800" stop="20260921190000 +0800" channel="c1"></programme>
              <programme start="20260921183000 +0800" stop="20260921190000 +0800" channel="c1">
                <title>also good</title>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertThat(collected.programmes.map { it.title }).containsExactly("good", "also good").inOrder()
        assertThat(result.programmes).isEqualTo(2)
        assertThat(result.skipped).isEqualTo(3)
        assertThat(result.malformed).isFalse()
    }

    @Test
    fun `a stop before the start is replaced by the default duration`() {
        val (collected, _) = parse(
            """
            <tv>
              <programme start="20260921183000 +0800" stop="20260921180000 +0800" channel="c1">
                <title>Backwards</title>
              </programme>
            </tv>
            """.trimIndent(),
        )
        val programme = collected.programmes.single()
        assertThat(programme.stopMs - programme.startMs).isEqualTo(XmltvTime.DEFAULT_DURATION_MS)
    }

    @Test
    fun `a document that ends inside a tag reports malformed but keeps what it already emitted`() {
        val (collected, result) = parse(
            """
            <tv>
              <programme start="20260921183000 +0800" stop="20260921190000 +0800" channel="c1">
                <title>complete</title>
              </programme>
              <programme start="20260921190000 +0800" stop="20260921193000 +0800" channel="c1">
                <title>truncated
            """.trimIndent(),
        )
        assertThat(collected.programmes.map { it.title }).containsExactly("complete")
        assertThat(result.malformed).isTrue()
    }

    @Test
    fun `an empty or textless document is not an error`() {
        val (collected, result) = parse("<tv></tv>")
        assertThat(collected.programmes).isEmpty()
        assertThat(result.programmes).isEqualTo(0)
        assertThat(result.malformed).isFalse()
    }

    @Test
    fun `whitespace around titles is trimmed and blank descriptions become null`() {
        val (collected, _) = parse(
            """
            <tv>
              <programme start="20260921183000 +0800" stop="20260921190000 +0800" channel="c1">
                <title>
                  Spaced title
                </title>
                <desc>   </desc>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertThat(collected.programmes.single().title).isEqualTo("Spaced title")
        assertThat(collected.programmes.single().desc).isNull()
    }

    @Test
    fun `a very long description is truncated at the cap instead of growing without bound`() {
        val huge = "x".repeat(XmltvPullParser.MAX_TEXT_LENGTH * 3)
        val (collected, _) = parse(
            """
            <tv>
              <programme start="20260921183000 +0800" stop="20260921190000 +0800" channel="c1">
                <title>t</title><desc>$huge</desc>
              </programme>
            </tv>
            """.trimIndent(),
        )
        assertThat(collected.programmes.single().desc!!.length)
            .isEqualTo(XmltvPullParser.MAX_TEXT_LENGTH)
    }

    @Test
    fun `the channel index maps the id and every normalized display name`() {
        val (collected, _) = parse(
            """
            <tv>
              <channel id="CCTV1.cn"><display-name>CCTV-1</display-name></channel>
              <channel id="CCTV2.cn"><display-name>CCTV-2 财经</display-name></channel>
            </tv>
            """.trimIndent(),
        )
        val index = epgChannelIndex(collected.channels)
        assertThat(index.byId).containsEntry("CCTV1.cn", "CCTV1.cn")
        assertThat(index.byNameKey).containsEntry("cctv-1", listOf("CCTV1.cn"))
        assertThat(index.byNameKey).containsEntry("cctv-2财经", listOf("CCTV2.cn"))
    }
}
