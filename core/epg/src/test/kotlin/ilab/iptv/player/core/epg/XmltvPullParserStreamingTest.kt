package ilab.iptv.player.core.epg

import com.google.common.truth.Truth.assertThat
import java.io.Reader
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The streaming claim, measured rather than asserted in prose.
 *
 * A guide of the size the job names ("XMLTV 可能几十 MB") is generated procedurally — no fixture file,
 * so the test's own heap does not hold the document either — and read through a [Reader] that:
 *
 * 1. **refuses `mark`/`reset`** (a parser that rewinds cannot stream a socket), and
 * 2. **counts every character it has produced**, so the test can ask *when* the rows came out.
 *
 * The second point is the one that matters: a parser that read the whole document and then walked it
 * would emit nothing until ~100% of the characters were consumed. The assertion below is that the
 * first programme is handed to the sink after roughly one buffer, i.e. the document is consumed in
 * step with the rows coming out — which is what "不整文件入内存" has to mean to be worth anything.
 */
class XmltvPullParserStreamingTest {

    /** Hands out a generated XMLTV document, forwards only, and keeps score of how much it produced. */
    private class GeneratedGuideReader(
        private val programmes: Int,
        private val titleChars: Int = 40,
    ) : Reader() {

        private val head = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n" +
            "<channel id=\"CCTV1.cn\"><display-name>CCTV-1</display-name></channel>\n"
        private val tail = "</tv>\n"

        /** Characters the parser has actually asked for — the read-ahead is a property of the parser. */
        var produced: Long = 0
            private set

        /** Set when someone tries to rewind; a streaming reader has no such thing. */
        var rewound = false
            private set

        private var headPos = 0
        private var tailPos = 0
        private var row = 0
        private var rowPos = 0
        private val rowText = StringBuilder()

        private fun rowOf(index: Int): String {
            val minute = (index % 60).toString().padStart(2, '0')
            val hour = ((index / 60) % 24).toString().padStart(2, '0')
            val title = ("P$index " + "x".repeat(titleChars)).take(titleChars)
            return "<programme start=\"20260921${hour}${minute}00 +0800\" " +
                "stop=\"20260921${hour}${minute}00 +0800\" channel=\"CCTV1.cn\">" +
                "<title>$title</title></programme>\n"
        }

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var written = 0
            while (written < len) {
                val source: CharSequence
                val pos: Int
                when {
                    headPos < head.length -> {
                        source = head
                        pos = headPos
                    }

                    row < programmes -> {
                        if (rowText.isEmpty()) rowText.append(rowOf(row))
                        source = rowText
                        pos = rowPos
                    }

                    tailPos < tail.length -> {
                        source = tail
                        pos = tailPos
                    }

                    else -> {
                        if (written == 0) return -1
                        produced += written.toLong()
                        return written
                    }
                }
                cbuf[off + written] = source[pos]
                written++
                when {
                    headPos < head.length -> headPos++
                    row < programmes -> {
                        rowPos++
                        if (rowPos >= rowText.length) {
                            rowText.setLength(0)
                            rowPos = 0
                            row++
                        }
                    }

                    else -> tailPos++
                }
            }
            produced += written.toLong()
            return written
        }

        override fun close() = Unit

        override fun mark(readAheadLimit: Int) {
            rewound = true
        }

        override fun reset() {
            rewound = true
        }

        override fun markSupported(): Boolean = false
    }

    @Test
    fun `consumes the guide in step with the rows instead of buffering it`() {
        val total = 40_000
        val reader = GeneratedGuideReader(total)
        val parser = XmltvPullParser()
        var firstEmissionAt: Long = -1
        var emitted = 0

        val result = runBlocking {
            parser.parse(
                input = reader,
                onProgramme = {
                    if (firstEmissionAt < 0) firstEmissionAt = reader.produced
                    emitted++
                },
            )
        }

        assertThat(result.programmes).isEqualTo(total)
        assertThat(emitted).isEqualTo(total)
        assertThat(reader.rewound).isFalse()

        // The whole guide is several megabytes...
        assertThat(reader.produced).isGreaterThan(4L * 1024 * 1024)
        // ... and the first row came out after the parser's own read buffer, not after the file.
        assertThat(firstEmissionAt).isLessThan(XmltvPullParser.BUFFER_CHARS.toLong() * 2)
        assertThat(firstEmissionAt).isLessThan(reader.produced / 50)
    }

    @Test
    fun `a guide far larger than the read buffer stays correct at the end as well as the start`() {
        val total = 5_000
        val reader = GeneratedGuideReader(total, titleChars = 200)
        val parser = XmltvPullParser()
        var lastId = ""

        val result = runBlocking {
            parser.parse(
                input = reader,
                onProgramme = { lastId = it.epgChannelId },
            )
        }

        assertThat(result.programmes).isEqualTo(total)
        assertThat(lastId).isEqualTo("CCTV1.cn")
        // One read buffer plus the current element is the whole working set; nothing here scales with
        // the document size, which is why the same code path holds a 40 MB guide.
        assertThat(result.malformed).isFalse()
    }
}
