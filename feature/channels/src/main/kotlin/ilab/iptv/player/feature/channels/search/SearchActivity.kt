package ilab.iptv.player.feature.channels.search

import android.os.Bundle
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.common.LogCategory
import ilab.iptv.player.core.common.Logger
import ilab.iptv.player.core.ui.player.PlayerContract
import ilab.iptv.player.feature.channels.R
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * P3-2's search screen: type with the remote's number keys or walk the on-screen letter grid with the
 * direction keys, and get channel-name and programme-name hits.
 *
 * TWO ZONES, ONE CURSOR (§8.2's discipline, applied to a screen with no list-of-lists): the keypad and
 * the results are separate focus areas, and this Activity decides every move itself instead of leaving
 * it to `focusSearch`. DOWN at the keypad's last row enters the results; UP on the first result goes
 * back to the keypad; the pure parts of that (the grid, the typed buffer) live in [SearchKeypad] and
 * [SearchInput] and are unit-tested.
 *
 * OK on a channel hit and OK on a programme hit do the same thing: open the player on that channel.
 * That IS the "跳到当前" behaviour P3-1 already gives a programme row (its `EpgGridActivity` opens
 * `PlayerContract` for the channel under the cursor), so this screen re-uses that entry point instead
 * of inventing a second one.
 */
@AndroidEntryPoint
class SearchActivity : ComponentActivity() {

    @Inject
    lateinit var logger: Logger

    private val viewModel: SearchViewModel by viewModels()
    private val input = SearchInput()

    private lateinit var queryView: TextView
    private lateinit var statusView: TextView
    private lateinit var keypadView: LinearLayout
    private lateinit var resultsView: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var keyCells: List<List<TextView>>

    private var zone = Zone.KEYPAD
    private var cursor = SearchKeypad.INITIAL
    private var resultIndex = 0
    private var renderedHits: List<SearchHit> = emptyList()

    private val times = SimpleDateFormat("HH:mm", Locale.getDefault())

    private enum class Zone { KEYPAD, RESULTS }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        queryView = findViewById(R.id.search_query)
        statusView = findViewById(R.id.search_status)
        keypadView = findViewById(R.id.search_keypad)
        resultsView = findViewById(R.id.search_results)
        emptyView = findViewById(R.id.search_empty)
        keyCells = buildKeypad()
        renderQuery()

        lifecycleScope.launch {
            viewModel.state.collect { state -> render(state) }
        }
    }

    override fun onResume() {
        super.onResume()
        logger.d(
            LogCategory.UI,
            EventCodes.UI_SCREEN_OPEN,
            "search screen opened",
            mapOf("screen" to SCREEN_NAME),
        )
        focusTarget()
    }

    // ---------------------------------------------------------------- keys

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // First BACK clears what was typed (the common "oops"), the second leaves the screen.
                // P3-7 item 1: the rule lives in SearchBackPolicy so the audit table and the code are
                // the same statement, and a test pins both halves.
                when (SearchBackPolicy.decide(input.text)) {
                    SearchBackAction.CLEAR_QUERY -> {
                        input.clear()
                        zone = Zone.KEYPAD
                        apply()
                        return true
                    }

                    SearchBackAction.LEAVE_SCREEN -> return super.onKeyDown(keyCode, event)
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> return moveCursor(KeypadMove.UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> return moveCursor(KeypadMove.DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> return moveCursor(KeypadMove.LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> return moveCursor(KeypadMove.RIGHT)

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                return activate()

            KeyEvent.KEYCODE_DEL -> {
                input.backspace()
                apply()
                return true
            }
        }
        // The number keys are the fast path of P3-2: a channel number is the shortest possible query,
        // and the digits row of the grid lights up so the user sees where the input came from.
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            typeDigit(keyCode - KeyEvent.KEYCODE_0)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun moveCursor(move: KeypadMove): Boolean {
        if (zone == Zone.KEYPAD) {
            val onLastRow = cursor.row == SearchKeypad.ROWS.lastIndex
            if (move == KeypadMove.DOWN && onLastRow && renderedHits.isNotEmpty()) {
                zone = Zone.RESULTS
                resultIndex = 0
                focusTarget()
                return true
            }
            cursor = SearchKeypad.move(cursor, move)
            focusTarget()
            return true
        }
        when (move) {
            KeypadMove.UP -> {
                if (resultIndex == 0) {
                    zone = Zone.KEYPAD
                } else {
                    resultIndex--
                }
            }

            KeypadMove.DOWN -> resultIndex = (resultIndex + 1).coerceAtMost(renderedHits.lastIndex)
            KeypadMove.LEFT, KeypadMove.RIGHT -> Unit
        }
        focusTarget()
        return true
    }

    private fun activate(): Boolean {
        if (zone == Zone.KEYPAD) {
            when (val action = SearchKeypad.cellAt(cursor).action) {
                is KeypadAction.Insert -> input.append(action.text.first())
                KeypadAction.Backspace -> input.backspace()
                KeypadAction.Clear -> input.clear()
            }
            apply()
            return true
        }
        renderedHits.getOrNull(resultIndex)?.let { open(it) }
        return true
    }

    private fun typeDigit(digit: Int) {
        input.append(digit.toString().first())
        cursor = SearchKeypad.positionOfDigit(digit)
        zone = Zone.KEYPAD
        apply()
    }

    /** One place where "the buffer changed" becomes "the screen asks for results". */
    private fun apply() {
        viewModel.setQuery(input.text)
        renderQuery()
        focusTarget()
    }

    /**
     * The player entry point of P3-1/P3-2: opening the channel is what "跳到当前" means for a
     * programme row, and the same `PlayerContract` intent the browse list and the EPG grid use.
     */
    private fun open(hit: SearchHit) {
        startActivity(PlayerContract.intent(this, hit.channelId))
    }

    // ---------------------------------------------------------------- rendering

    private fun buildKeypad(): List<List<TextView>> = SearchKeypad.ROWS.map { row ->
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cells = row.map { cell ->
            TextView(this).apply {
                text = cell.label
                textSize = 20f
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                disableDefaultFocusHighlight()
                setBackgroundResource(R.drawable.bg_search_key)
                minimumWidth = dp(KEY_MIN_WIDTH_DP)
                minimumHeight = dp(KEY_MIN_HEIGHT_DP)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    when (val action = cell.action) {
                        is KeypadAction.Insert -> input.append(action.text.first())
                        KeypadAction.Backspace -> input.backspace()
                        KeypadAction.Clear -> input.clear()
                    }
                    zone = Zone.KEYPAD
                    apply()
                }
            }
        }
        cells.forEach { rowView.addView(it) }
        keypadView.addView(rowView)
        cells
    }

    private fun render(state: SearchUiState) {
        statusView.text = when {
            state.indexError != null -> getString(R.string.search_index_failed, state.indexError)
            !state.indexReady -> getString(R.string.search_index_loading)
            state.query.isEmpty() -> getString(
                R.string.search_index_ready,
                state.channelCount,
                state.programmeCount,
            )

            state.hits.isEmpty() -> getString(R.string.search_hits_none)
            else -> getString(R.string.search_hits_format, state.hits.size)
        }
        renderResults(state.hits)
        focusTarget()
    }

    /** Rows are rebuilt only when the hit list actually changed, so the cursor is not reset per frame. */
    private fun renderResults(hits: List<SearchHit>) {
        if (hits == renderedHits) return
        renderedHits = hits
        resultsView.removeAllViews()
        emptyView.visibility = if (hits.isEmpty()) View.VISIBLE else View.GONE
        if (hits.isEmpty() && zone == Zone.RESULTS) zone = Zone.KEYPAD
        hits.forEach { hit ->
            resultsView.addView(buildRow(hit))
        }
        resultIndex = resultIndex.coerceIn(0, maxOf(hits.lastIndex, 0))
    }

    private fun buildRow(hit: SearchHit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        isClickable = true
        disableDefaultFocusHighlight()
        setBackgroundResource(R.drawable.bg_search_row)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        addView(
            TextView(this@SearchActivity).apply {
                text = hit.title
                textSize = 22f
                setTextColor(ROW_TITLE_COLOR)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        addView(
            TextView(this@SearchActivity).apply {
                text = rowSubtitle(hit)
                textSize = 16f
                setTextColor(ROW_SUBTITLE_COLOR)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            },
        )
        setOnClickListener { open(hit) }
    }

    /** A programme row says when it starts — "新闻联播" alone does not answer "can I watch it now". */
    private fun rowSubtitle(hit: SearchHit): String = when (hit) {
        is ChannelHit -> hit.subtitle
        is ProgrammeHit -> getString(
            R.string.search_programme_subtitle,
            times.format(Date(hit.startMs)),
            times.format(Date(hit.stopMs)),
            hit.channelName,
        )
    }

    private fun renderQuery() {
        val text = input.text
        queryView.text = if (text.isEmpty()) {
            getString(R.string.search_query_empty)
        } else {
            getString(R.string.search_query_format, text)
        }
    }

    private fun focusTarget() {
        if (zone == Zone.KEYPAD) {
            keyCells.getOrNull(cursor.row)?.getOrNull(cursor.col)?.requestFocus()
            return
        }
        if (renderedHits.isEmpty()) {
            zone = Zone.KEYPAD
            keyCells.getOrNull(cursor.row)?.getOrNull(cursor.col)?.requestFocus()
            return
        }
        resultIndex = resultIndex.coerceIn(0, renderedHits.lastIndex)
        resultsView.getChildAt(resultIndex)?.requestFocus()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * docs/02 §8.2: the focused state is drawn by our own background, not by the system highlight.
     * `setDefaultFocusHighlightEnabled` is API 26, and this module ships to API 21 — below that the
     * system highlight has nothing to draw over our focus state anyway.
     */
    private fun View.disableDefaultFocusHighlight() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
    }

    private companion object {
        const val SCREEN_NAME = "Search"
        const val KEY_MIN_WIDTH_DP = 56
        const val KEY_MIN_HEIGHT_DP = 48
        const val ROW_TITLE_COLOR = 0xFFE6E6E6.toInt()
        const val ROW_SUBTITLE_COLOR = 0xFF9A9AA5.toInt()
    }
}
