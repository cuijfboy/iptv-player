package ilab.iptv.player.feature.settings.diag

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel
import org.junit.Test

/**
 * The live-log half of docs/03 §7.2: 级别过滤 / 分类过滤 / 关键字搜索 / 按会话查看, over the events the
 * panel hands in (it never reads the ring itself — the ring stays the only buffer).
 */
class DiagLogFilterTest {

    private fun event(
        seq: Long,
        level: LogLevel = LogLevel.INFO,
        category: LogCategory = LogCategory.SOURCE,
        code: String = "SRC_FETCH_OK",
        message: String = "fetched",
        fields: Map<String, Any?> = emptyMap(),
        sessionId: String = "refresh-1",
    ) = LogEvent(
        seq = seq,
        ts = 1_700_000_000_000L + seq,
        elapsedMs = seq * 10,
        level = level,
        category = category,
        code = code,
        message = message,
        fields = fields,
        error = null,
        thread = "test",
        screen = null,
        sessionId = sessionId,
    )

    private val events = listOf(
        event(1, level = LogLevel.DEBUG, code = "UI_SCREEN_OPEN", message = "settings opened", category = LogCategory.UI),
        event(2, level = LogLevel.INFO, code = "SRC_FETCH_OK", message = "fetched 412 entries", fields = mapOf("provider" to "demo")),
        event(3, level = LogLevel.WARN, code = "SRC_FETCH_FAIL", message = "timeout", fields = mapOf("host" to "cdn.example.com")),
        event(4, level = LogLevel.ERROR, code = "DB_FAIL", message = "write failed", category = LogCategory.DB),
        event(5, level = LogLevel.INFO, code = "PLAY_FIRST_FRAME", message = "first frame", category = LogCategory.PLAYER, sessionId = "play-9"),
    )

    @Test
    fun `the default query keeps INFO and above and drops DEBUG`() {
        val shown = DiagLogFilter.apply(events, DiagLogQuery())

        assertThat(shown.map { it.seq }).containsExactly(2L, 3L, 4L, 5L).inOrder()
    }

    @Test
    fun `the level filter is a floor, so VERBOSE shows everything`() {
        val shown = DiagLogFilter.apply(events, DiagLogQuery(minLevel = LogLevel.VERBOSE))

        assertThat(shown.map { it.seq }).containsExactly(1L, 2L, 3L, 4L, 5L).inOrder()
    }

    @Test
    fun `a category filter keeps only that category`() {
        val shown = DiagLogFilter.apply(events, DiagLogQuery(categories = setOf(LogCategory.PLAYER)))

        assertThat(shown.map { it.code }).containsExactly("PLAY_FIRST_FRAME")
    }

    @Test
    fun `one session can be folded out of the whole ring`() {
        val shown = DiagLogFilter.apply(events, DiagLogQuery(sessionId = "play-9"))

        assertThat(shown.map { it.seq }).containsExactly(5L)
    }

    @Test
    fun `the keyword searches the code, the message, a field key and a field value`() {
        assertThat(DiagLogFilter.apply(events, DiagLogQuery(keyword = "DB_FAIL")).map { it.seq }).containsExactly(4L)
        assertThat(DiagLogFilter.apply(events, DiagLogQuery(keyword = "412")).map { it.seq }).containsExactly(2L)
        assertThat(DiagLogFilter.apply(events, DiagLogQuery(keyword = "host")).map { it.seq }).containsExactly(3L)
        assertThat(DiagLogFilter.apply(events, DiagLogQuery(keyword = "CDN.EXAMPLE")).map { it.seq }).containsExactly(3L)
    }

    @Test
    fun `the tail is capped so a full ring cannot be rendered whole`() {
        val many = (1L..500L).map { event(it, message = "line $it") }

        val shown = DiagLogFilter.apply(many, DiagLogQuery(minLevel = LogLevel.VERBOSE))

        assertThat(shown).hasSize(DiagLogFilter.DEFAULT_LIMIT)
        // A tail: the newest lines survive, the oldest are the ones dropped.
        assertThat(shown.first().seq).isEqualTo(500L - DiagLogFilter.DEFAULT_LIMIT + 1)
        assertThat(shown.last().seq).isEqualTo(500L)
    }

    @Test
    fun `the level button walks every level and comes back`() {
        var level = LogLevel.INFO
        val seen = mutableListOf(level)
        repeat(LogLevel.entries.size) {
            level = DiagLogFilter.cycleLevel(level)
            seen += level
        }

        assertThat(seen.first()).isEqualTo(seen.last())
        assertThat(seen.distinct()).hasSize(LogLevel.entries.size)
    }

    @Test
    fun `the category button walks all categories and then back to all`() {
        var category: LogCategory? = null
        val seen = mutableListOf<LogCategory?>()
        repeat(LogCategory.entries.size + 1) {
            category = DiagLogFilter.cycleCategory(category)
            seen += category
        }

        assertThat(seen.first()).isEqualTo(LogCategory.entries.first())
        assertThat(seen.last()).isNull()
        assertThat(seen.filterNotNull()).containsExactlyElementsIn(LogCategory.entries)
    }

    @Test
    fun `the header line says what is being shown`() {
        val description = DiagLogFilter.describe(
            DiagLogQuery(minLevel = LogLevel.WARN, categories = setOf(LogCategory.SOURCE), keyword = "timeout"),
        )

        assertThat(description).contains("WARN")
        assertThat(description).contains("SOURCE")
        assertThat(description).contains("timeout")
        assertThat(DiagLogFilter.describe(DiagLogQuery())).contains("全部分类")
    }
}
