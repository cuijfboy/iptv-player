package ilab.iptv.player.feature.channels.search

/** What one on-screen key does when the remote's OK lands on it. */
sealed interface KeypadAction {
    data class Insert(val text: String) : KeypadAction
    data object Backspace : KeypadAction
    data object Clear : KeypadAction
}

data class KeypadCell(val label: String, val action: KeypadAction)

data class KeypadPosition(val row: Int, val col: Int)

enum class KeypadMove { UP, DOWN, LEFT, RIGHT }

/**
 * The remote-only input scheme of P3-2 ("输入用遥控数字/方向键的最小可用方案").
 *
 * A TV has no soft keyboard, so the letters live on screen as a grid the direction keys walk over:
 * one digits row (0–9) and four letter rows (A–Z plus 删除/清空). The grid is pure data and the
 * cursor movement is pure arithmetic, so "LEFT at the left edge", "DOWN into a shorter row" and
 * "where does the cursor start" are unit-tested instead of being discovered with a remote in hand.
 *
 * The number keys of the physical remote also type straight into the query (a channel number is the
 * fastest query there is), which is why the digits row exists here too: typing `13` with the remote
 * lights up the same cells, so the on-screen grid always shows where the input came from.
 */
object SearchKeypad {

    private val DIGITS: List<KeypadCell> = (0..9).map { digit ->
        KeypadCell(label = digit.toString(), action = KeypadAction.Insert(digit.toString()))
    }

    private val LETTER_ROWS: List<List<KeypadCell>> = listOf("ABCDEFGH", "IJKLMNOP", "QRSTUVWX", "YZ")
        .map { row ->
            row.map { letter -> KeypadCell(label = letter.toString(), action = KeypadAction.Insert(letter.lowercase())) }
        }

    /** Row 0 is the digits; the last letter row carries 删除 and 清空. */
    val ROWS: List<List<KeypadCell>> = buildList {
        add(DIGITS)
        LETTER_ROWS.forEachIndexed { index, row ->
            add(
                if (index == LETTER_ROWS.lastIndex) {
                    row + listOf(
                        KeypadCell(label = "删除", action = KeypadAction.Backspace),
                        KeypadCell(label = "清空", action = KeypadAction.Clear),
                    )
                } else {
                    row
                },
            )
        }
    }

    /** Where the remote starts: the digit 1 — the first thing a channel-number query needs. */
    val INITIAL: KeypadPosition = KeypadPosition(row = 0, col = 1)

    fun cellAt(position: KeypadPosition): KeypadCell = ROWS[position.row][position.col]

    /**
     * Walks the grid. Movement never wraps: at an edge the cursor stays put, which is what a remote
     * user expects (a wrap would teleport the highlight to the other side of the screen). Moving
     * DOWN or UP into a shorter row clamps the column, so the cursor always stays on a real key.
     */
    fun move(from: KeypadPosition, move: KeypadMove): KeypadPosition {
        val row = from.row.coerceIn(0, ROWS.lastIndex)
        val col = from.col.coerceIn(0, ROWS[row].lastIndex)
        return when (move) {
            KeypadMove.LEFT -> KeypadPosition(row, (col - 1).coerceAtLeast(0))
            KeypadMove.RIGHT -> KeypadPosition(row, (col + 1).coerceAtMost(ROWS[row].lastIndex))
            KeypadMove.UP -> KeypadPosition((row - 1).coerceAtLeast(0), col).clampedColumn()
            KeypadMove.DOWN -> KeypadPosition((row + 1).coerceAtMost(ROWS.lastIndex), col).clampedColumn()
        }
    }

    /** The cell a digit key of the physical remote highlights (its row-0 counterpart). */
    fun positionOfDigit(digit: Int): KeypadPosition = KeypadPosition(row = 0, col = digit.coerceIn(0, 9))

    private fun KeypadPosition.clampedColumn(): KeypadPosition =
        KeypadPosition(row, col.coerceAtMost(ROWS[row].lastIndex))
}
