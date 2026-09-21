package ilab.iptv.player.core.source

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.source.parser.PlaylistFormat
import ilab.iptv.player.core.source.parser.TxtParser
import org.junit.Test

class TxtParserTest {

    private fun parse(text: String) = TxtParser.parse(text, sourceId = "test-source")

    @Test
    fun `two field rows parse without a group`() {
        val outcome = parse(
            """
            CCTV-1,http://a.example/cctv1/index.m3u8
            CCTV-2,http://a.example/cctv2/index.m3u8
            """.trimIndent(),
        )

        assertThat(outcome.format).isEqualTo(PlaylistFormat.TXT)
        assertThat(outcome.entries).hasSize(2)
        assertThat(outcome.entries[0].name).isEqualTo("CCTV-1")
        assertThat(outcome.entries[0].groupTitle).isNull()
    }

    @Test
    fun `three field rows carry their own group and extra commas stay in the group`() {
        val outcome = parse(
            """
            CCTV-1,http://a.example/cctv1/index.m3u8,央视
            CCTV-3,http://a.example/cctv3/index.m3u8,新闻,财经
            """.trimIndent(),
        )

        assertThat(outcome.entries.map { it.groupTitle }).containsExactly("央视", "新闻,财经").inOrder()
    }

    @Test
    fun `genre sections apply to the rows under them and an explicit group wins`() {
        val outcome = parse(Fixtures.text("txt/genre.txt"))

        assertThat(outcome.entries).hasSize(5)
        assertThat(outcome.entries.map { it.groupTitle })
            .containsExactly("央视频道", "央视频道", "卫视频道", "上海", null)
            .inOrder()
    }

    @Test
    fun `malformed rows are counted and comments are ignored`() {
        val outcome = parse(Fixtures.text("txt/malformed.txt"))

        assertThat(outcome.entries.map { it.name }).containsExactly("CCTV-2", "CCTV-3").inOrder()
        assertThat(outcome.skipped).isEqualTo(3)
    }

    @Test
    fun `empty and comment-only text parses to nothing`() {
        assertThat(parse("").entries).isEmpty()
        assertThat(parse("# just a comment\n#another\n").skipped).isEqualTo(0)
    }
}
