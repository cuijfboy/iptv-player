package ilab.iptv.player.feature.settings.diag

import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.LogLevel

/**
 * What the live-log half of the diagnostics panel (docs/03 §7.2) is set to: 级别过滤, 分类过滤,
 * 事件码/关键字搜索, and "按会话查看" (fold one `sessionId`).
 */
data class DiagLogQuery(
    val minLevel: LogLevel = LogLevel.INFO,
    /** Empty set = every category, which is what the panel starts on. */
    val categories: Set<LogCategory> = emptySet(),
    val keyword: String = "",
    val sessionId: String? = null,
)

/**
 * The filter that turns the memory ring's contents into what the panel shows.
 *
 * Pure and side-effect free, over the events it is handed: it **never reads the ring itself**, so the
 * panel's "what you see" is testable without a device (the ring stays the single buffer — the panel
 * must not open a second one, which is the dispatch's "复用 `MemoryRingSink` 的内容，不新开缓冲").
 */
object DiagLogFilter {

    /** Newest [DEFAULT_LIMIT] lines after filtering: a TV screen shows a tail, not a file. */
    const val DEFAULT_LIMIT: Int = 200

    /**
     * Events matching [query], oldest-first so the tail reads chronologically; at most [limit] newest
     * matches survive (a 2000-event ring must not be rendered whole every second).
     */
    fun apply(events: List<LogEvent>, query: DiagLogQuery, limit: Int = DEFAULT_LIMIT): List<LogEvent> {
        val needle = query.keyword.trim()
        val matched = events.filter { event ->
            event.level >= query.minLevel &&
                (query.categories.isEmpty() || event.category in query.categories) &&
                (query.sessionId == null || event.sessionId == query.sessionId) &&
                (needle.isEmpty() || matches(event, needle))
        }
        if (limit <= 0 || matched.size <= limit) return matched
        return matched.subList(matched.size - limit, matched.size)
    }

    private fun matches(event: LogEvent, needle: String): Boolean {
        if (event.code.contains(needle, ignoreCase = true)) return true
        if (event.message.contains(needle, ignoreCase = true)) return true
        if (event.category.name.contains(needle, ignoreCase = true)) return true
        if (event.sessionId.contains(needle, ignoreCase = true)) return true
        if (event.fields.keys.any { it.contains(needle, ignoreCase = true) }) return true
        return event.fields.values.any { it?.toString()?.contains(needle, ignoreCase = true) == true }
    }

    /**
     * The level cycle behind the panel's one button, in enum order (`VERBOSE → DEBUG → … → FATAL →
     * VERBOSE`): a filtered-out level must always be reachable again, so the cycle is a full ring.
     */
    fun cycleLevel(current: LogLevel): LogLevel {
        val levels = LogLevel.entries
        return levels[(current.ordinal + 1) % levels.size]
    }

    /** `null` = 全部分类; the button walks the categories and comes back to all. */
    fun cycleCategory(current: LogCategory?): LogCategory? {
        val categories = LogCategory.entries
        if (current == null) return categories.first()
        val next = current.ordinal + 1
        return if (next >= categories.size) null else categories[next]
    }

    /** The on-screen line for the current filter, so a screenshot says what it is showing. */
    fun describe(query: DiagLogQuery): String = buildString {
        append(query.minLevel.name)
        append(" / ")
        append(query.categories.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name } ?: "全部分类")
        if (query.keyword.isNotBlank()) append(" / 搜索 ").append(query.keyword.trim())
        query.sessionId?.let { append(" / 会话 ").append(it) }
    }
}
