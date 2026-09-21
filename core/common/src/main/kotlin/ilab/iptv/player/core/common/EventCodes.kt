package ilab.iptv.player.core.common

/**
 * Event-code registry (docs/03 §3.3). The single source of truth for the `code` field of every
 * [LogEvent]; business code must reference these constants instead of writing string literals
 * (the P0-7 CI guard checks for literals).
 *
 * Format: `<domain>_<object>_<result>`, upper snake case. A code is **stable**: prose in
 * `message` may change, the code may not — dashboards, the troubleshooting manual (docs/03 §12)
 * and the metric bridge are keyed on it.
 */
object EventCodes {

    // --- APP / UI ---
    const val APP_START = "APP_START"
    const val APP_STOP = "APP_STOP"
    const val UI_SCREEN_OPEN = "UI_SCREEN_OPEN"
    const val UI_FRAME_JANK = "UI_FRAME_JANK"

    // --- NET ---
    const val NET_REQ_OK = "NET_REQ_OK"
    const val NET_REQ_FAIL = "NET_REQ_FAIL"
    const val NET_CHARSET_FALLBACK = "NET_CHARSET_FALLBACK"

    // --- SOURCE / VALIDATE ---
    const val SRC_FETCH_OK = "SRC_FETCH_OK"
    const val SRC_FETCH_FAIL = "SRC_FETCH_FAIL"
    const val SRC_PARSE_OK = "SRC_PARSE_OK"
    /**
     * The fetch worked but the playlist could not be parsed (docs/03 §3.3 note, 2026-09-22).
     * [SRC_FETCH_FAIL] covers retrieval only, so "the source is unreachable" and "the source
     * changed its format" stay separable in the troubleshooting manual (docs/03 §12).
     */
    const val SRC_PARSE_FAIL = "SRC_PARSE_FAIL"
    const val SRC_DEDUPE = "SRC_DEDUPE"
    const val SRC_REFRESH_START = "SRC_REFRESH_START"
    const val SRC_REFRESH_DONE = "SRC_REFRESH_DONE"
    const val SRC_REFRESH_SKIP = "SRC_REFRESH_SKIP"
    const val VAL_SHALLOW_OK = "VAL_SHALLOW_OK"
    const val VAL_SHALLOW_FAIL = "VAL_SHALLOW_FAIL"
    const val VAL_DEEP_OK = "VAL_DEEP_OK"
    const val VAL_DEEP_FAIL = "VAL_DEEP_FAIL"
    const val SRC_SCORE = "SRC_SCORE"
    const val SRC_SELECT = "SRC_SELECT"

    // --- EPG ---
    const val EPG_FETCH_OK = "EPG_FETCH_OK"
    const val EPG_FETCH_FAIL = "EPG_FETCH_FAIL"
    const val EPG_PARSE_OK = "EPG_PARSE_OK"
    const val EPG_MATCH_HIT = "EPG_MATCH_HIT"
    const val EPG_MATCH_MISS = "EPG_MATCH_MISS"
    const val EPG_COVERAGE = "EPG_COVERAGE"

    // --- DB ---
    const val DB_UPSERT = "DB_UPSERT"
    const val DB_FAIL = "DB_FAIL"

    // --- PLAYER ---
    const val PLAY_PREPARE_START = "PLAY_PREPARE_START"
    const val PLAY_FIRST_FRAME = "PLAY_FIRST_FRAME"
    const val PLAY_PREPARE_FAIL = "PLAY_PREPARE_FAIL"
    const val PLAY_FAILOVER = "PLAY_FAILOVER"
    const val PLAY_STALL = "PLAY_STALL"
    const val PLAY_SWITCH_CHANNEL = "PLAY_SWITCH_CHANNEL"
    const val PLAY_END = "PLAY_END"
    const val PLAY_ENGINE_INIT = "PLAY_ENGINE_INIT"
    const val PLAY_ENGINE_RELEASE = "PLAY_ENGINE_RELEASE"
    /**
     * Audio focus, one event per change (P1-7 item 3, P1-7 §7.3.2). `reason` is the focus event
     * (`GAIN` / `LOSS` / `LOSS_TRANSIENT` / `LOSS_TRANSIENT_CAN_DUCK`): "the TV was taken by another
     * app" and "we were only ducked" have different remedies, so they must not share a code.
     */
    const val PLAY_FOCUS_CHANGE = "PLAY_FOCUS_CHANGE"
    /**
     * A retry released by the network coming back (P1-7 item 5, P1-7 §7.3.2). `attempt` is the
     * retry number for this session — a channel that keeps bouncing needs the count in one place.
     */
    const val PLAY_NET_RETRY = "PLAY_NET_RETRY"

    // --- SERVICE / WORK / PERF / CRASH ---
    /**
     * The playback foreground service entering / leaving the foreground (P1-7 items 1–2, §7.3.2).
     * `result` answers "did the foreground state actually take": a `START` with `result=failed` is
     * the Android 12 background-start rejection, which is otherwise invisible.
     */
    const val SERVICE_PLAYBACK_START = "SERVICE_PLAYBACK_START"
    const val SERVICE_PLAYBACK_STOP = "SERVICE_PLAYBACK_STOP"
    const val SERVICE_REFRESH_START = "SERVICE_REFRESH_START"
    const val SERVICE_REFRESH_STOP = "SERVICE_REFRESH_STOP"
    const val WORK_SCHEDULE = "WORK_SCHEDULE"
    const val WORK_RUN = "WORK_RUN"
    const val PERF_STARTUP = "PERF_STARTUP"
    const val PERF_EPG_GRID = "PERF_EPG_GRID"
    const val CRASH = "CRASH"
    const val ANR = "ANR"

    /**
     * Every registered code. Kept explicit (instead of reflection) so a code that is added or
     * removed shows up as a diff, and so the P0-7 CI guard can assert a logged code is registered.
     */
    val ALL: Set<String> = setOf(
        APP_START, APP_STOP, UI_SCREEN_OPEN, UI_FRAME_JANK,
        NET_REQ_OK, NET_REQ_FAIL, NET_CHARSET_FALLBACK,
        SRC_FETCH_OK, SRC_FETCH_FAIL, SRC_PARSE_OK, SRC_PARSE_FAIL, SRC_DEDUPE,
        SRC_REFRESH_START, SRC_REFRESH_DONE, SRC_REFRESH_SKIP,
        VAL_SHALLOW_OK, VAL_SHALLOW_FAIL, VAL_DEEP_OK, VAL_DEEP_FAIL,
        SRC_SCORE, SRC_SELECT,
        EPG_FETCH_OK, EPG_FETCH_FAIL, EPG_PARSE_OK,
        EPG_MATCH_HIT, EPG_MATCH_MISS, EPG_COVERAGE,
        DB_UPSERT, DB_FAIL,
        PLAY_PREPARE_START, PLAY_FIRST_FRAME, PLAY_PREPARE_FAIL, PLAY_FAILOVER,
        PLAY_STALL, PLAY_SWITCH_CHANNEL, PLAY_END, PLAY_ENGINE_INIT, PLAY_ENGINE_RELEASE,
        PLAY_FOCUS_CHANGE, PLAY_NET_RETRY,
        SERVICE_PLAYBACK_START, SERVICE_PLAYBACK_STOP,
        SERVICE_REFRESH_START, SERVICE_REFRESH_STOP,
        WORK_SCHEDULE, WORK_RUN,
        PERF_STARTUP, PERF_EPG_GRID,
        CRASH, ANR,
    )

    /** True when [code] is a registered event code (docs/03 §3.3). */
    fun isRegistered(code: String): Boolean = code in ALL
}
