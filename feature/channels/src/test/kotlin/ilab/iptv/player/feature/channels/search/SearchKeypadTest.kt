package ilab.iptv.player.feature.channels.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** docs/04 P3-2: the remote-only input grid (方向键 + 数字键的最小可用方案). */
class SearchKeypadTest {

    @Test
    fun `the grid is a digits row plus four letter rows`() {
        assertThat(SearchKeypad.ROWS).hasSize(5)
        assertThat(SearchKeypad.ROWS.first().map { it.label })
            .containsExactly("0", "1", "2", "3", "4", "5", "6", "7", "8", "9").inOrder()
        val letters = SearchKeypad.ROWS.drop(1).flatMap { row -> row.map { it.label } }
        assertThat(letters).containsAtLeast("A", "Z", "删除", "清空")
        assertThat(letters).hasSize(26 + 2)
    }

    @Test
    fun `the cursor starts on the digit one`() {
        assertThat(SearchKeypad.cellAt(SearchKeypad.INITIAL).label).isEqualTo("1")
    }

    @Test
    fun `movement clamps at the edges instead of wrapping`() {
        val leftEdge = KeypadPosition(row = 0, col = 0)
        assertThat(SearchKeypad.move(leftEdge, KeypadMove.LEFT)).isEqualTo(leftEdge)
        assertThat(SearchKeypad.move(leftEdge, KeypadMove.UP)).isEqualTo(leftEdge)
        val rightEdge = KeypadPosition(row = 0, col = 9)
        assertThat(SearchKeypad.move(rightEdge, KeypadMove.RIGHT)).isEqualTo(rightEdge)
        val bottomLeft = KeypadPosition(row = SearchKeypad.ROWS.lastIndex, col = 0)
        assertThat(SearchKeypad.move(bottomLeft, KeypadMove.DOWN)).isEqualTo(bottomLeft)
    }

    @Test
    fun `moving into a shorter row keeps the cursor on a real key`() {
        // Row 0 has ten keys, the letter rows eight; DOWN from column 9 must land on "H", not off the
        // end of the row.
        val from = KeypadPosition(row = 0, col = 9)
        val down = SearchKeypad.move(from, KeypadMove.DOWN)
        assertThat(down.row).isEqualTo(1)
        assertThat(down.col).isEqualTo(7)
        assertThat(SearchKeypad.cellAt(down).label).isEqualTo("H")
    }

    @Test
    fun `the physical number keys map onto the digits row`() {
        assertThat(SearchKeypad.cellAt(SearchKeypad.positionOfDigit(7)).label).isEqualTo("7")
        assertThat(SearchKeypad.positionOfDigit(99)).isEqualTo(KeypadPosition(0, 9))
    }

    @Test
    fun `the letter cells insert lowercase letters and the last row can delete and clear`() {
        val insert = SearchKeypad.ROWS[1][0]
        assertThat(insert.action).isEqualTo(KeypadAction.Insert("a"))
        val lastRow = SearchKeypad.ROWS.last()
        assertThat(lastRow).contains(KeypadCell("删除", KeypadAction.Backspace))
        assertThat(lastRow).contains(KeypadCell("清空", KeypadAction.Clear))
    }
}
