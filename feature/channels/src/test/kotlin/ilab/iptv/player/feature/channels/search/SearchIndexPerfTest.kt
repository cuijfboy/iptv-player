package ilab.iptv.player.feature.channels.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * docs/04 P3-2 acceptance: "1 s 内出结果".
 *
 * The corpus is the shape P2-7 measured on the real guide (658 channels, 19 879 programmes in the
 * window), so the number printed here is the number the acceptance is about. The assertion is the
 * acceptance line (p95 well under 1 s); the printed figures are what the verification record quotes,
 * the same way the EPG grid's benchmark reports its frames.
 */
class SearchIndexPerfTest {

    private val channelCount = 658
    private val programmeCount = 19_879

    @Test
    fun `a query over the real catalog size answers well inside the one second budget`() {
        val channels = (1..channelCount).map { index ->
            SearchChannel(
                channelId = index.toLong(),
                name = if (index % 7 == 0) "湖南卫视$index" else "CCTV$index 综合频道",
                number = index,
                groupKey = if (index % 2 == 0) "cctv" else "sat",
            )
        }
        val programmes = (1..programmeCount).map { index ->
            val channelId = (index % channelCount + 1).toLong()
            SearchProgramme(
                channelId = channelId,
                channelName = "CCTV$channelId 综合频道",
                title = if (index % 3 == 0) "新闻联播" else "节目$index",
                startMs = 1_700_000_000_000L + index * 60_000L,
                stopMs = 1_700_000_000_000L + (index + 1) * 60_000L,
            )
        }

        val buildStart = System.nanoTime()
        val index = SearchIndex.build(channels, programmes)
        val buildMs = (System.nanoTime() - buildStart) / 1_000_000

        val queries = listOf(
            "cctv1", "新闻", "xw", "1", "13", "cctv13", "湖南", "hnws", "综合", "节目2000",
            "cctv658", "卫视", "新闻联播", "cctv", "tv1", "658", "节目19999", "湖", "南w", "节目5",
        )
        val at = 1_700_000_500_000L
        // One warm-up pass so the measured numbers are not the first JIT-compiled call.
        index.search(queries.first(), at)
        val timings = queries.map { query ->
            val start = System.nanoTime()
            val hits = index.search(query, at)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertThat(hits).isNotNull()
            ms
        }.sorted()
        val total = timings.sum()
        val p95 = timings[(timings.size * 95 / 100).coerceAtMost(timings.lastIndex)]
        println(
            "SEARCH_BENCH channels=$channelCount programmes=$programmeCount keys=${index.keyCount} " +
                "buildMs=$buildMs queries=${queries.size} totalMs=$total " +
                "p50Ms=${timings[timings.size / 2]} p95Ms=$p95 maxMs=${timings.last()}",
        )

        assertThat(p95).isLessThan(PER_QUERY_BUDGET_MS)
        assertThat(total).isLessThan(QUERY_BUDGET_TOTAL_MS)
    }

    private companion object {
        /** The acceptance line: one query answers inside one second — with room for a slow CI box. */
        const val PER_QUERY_BUDGET_MS = 1_000L
        const val QUERY_BUDGET_TOTAL_MS = 5_000L
    }
}
