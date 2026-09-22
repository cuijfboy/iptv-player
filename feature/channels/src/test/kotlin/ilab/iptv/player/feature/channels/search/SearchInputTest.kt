package ilab.iptv.player.feature.channels.search

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** docs/04 P3-2: what the remote may type into the query. */
class SearchInputTest {

    @Test
    fun `letters and digits are accepted and folded to lowercase`() {
        val input = SearchInput()
        assertThat(input.append('C')).isTrue()
        assertThat(input.append('1')).isTrue()
        assertThat(input.text).isEqualTo("c1")
    }

    @Test
    fun `separators and CJK-only punctuation are rejected, CJK is accepted`() {
        val input = SearchInput()
        assertThat(input.append('-')).isFalse()
        assertThat(input.append(' ')).isFalse()
        assertThat(input.append('新')).isTrue()
        assertThat(input.text).isEqualTo("新")
    }

    @Test
    fun `backspace and clear report whether they changed anything`() {
        val input = SearchInput()
        assertThat(input.backspace()).isFalse()
        assertThat(input.clear()).isFalse()
        input.append('x')
        input.append('w')
        assertThat(input.backspace()).isTrue()
        assertThat(input.text).isEqualTo("x")
        assertThat(input.clear()).isTrue()
        assertThat(input.isEmpty).isTrue()
    }

    @Test
    fun `the buffer stops at its maximum length`() {
        val input = SearchInput(maxLength = 3)
        repeat(3) { input.append('a') }
        assertThat(input.append('b')).isFalse()
        assertThat(input.text).isEqualTo("aaa")
    }
}
