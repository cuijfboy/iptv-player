package ilab.iptv.player.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the docs/03 §3.3 registry: uppercase snake case, no duplicates, and the size matches the
 * table in the design doc (50 codes: 46 + the four P1-7 key-path codes of §3.3.1). A code added
 * without updating the doc, or one typo'd into a duplicate, fails here instead of silently splitting
 * a dashboard in production.
 */
class EventCodesTest {

    private val codeFormat = Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$")

    @Test
    fun `every registered code is upper snake case`() {
        val offenders = EventCodes.ALL.filterNot { codeFormat.matches(it) }
        assertThat(offenders).isEmpty()
    }

    @Test
    fun `registry has no duplicates and matches the docs 03 table`() {
        // counted from docs/03 §3.3 (46 codes, counting `/`-joined pairs as two codes)
        // + the four key-path codes of docs/03 §3.3.1 = 50
        assertThat(EventCodes.ALL).hasSize(50)
        assertThat(EventCodes.ALL.toList().distinct()).hasSize(50)
    }

    @Test
    fun `isRegistered reflects the registry`() {
        assertThat(EventCodes.isRegistered(EventCodes.PLAY_FIRST_FRAME)).isTrue()
        assertThat(EventCodes.isRegistered("NOT_A_CODE")).isFalse()
        assertThat(EventCodes.isRegistered("")).isFalse()
    }

    @Test
    fun `codes used by the logger skeleton are registered`() {
        assertThat(EventCodes.isRegistered(EventCodes.APP_START)).isTrue()
        assertThat(EventCodes.isRegistered(EventCodes.UI_SCREEN_OPEN)).isTrue()
        assertThat(EventCodes.isRegistered(EventCodes.CRASH)).isTrue()
    }
}
