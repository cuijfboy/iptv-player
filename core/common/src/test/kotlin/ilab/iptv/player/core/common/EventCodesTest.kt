package ilab.iptv.player.core.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Guards the docs/03 §3.3 registry: uppercase snake case, no duplicates, and the size matches the
 * table in the design doc (45 codes). A code added without updating the doc, or one typo'd into a
 * duplicate, fails here instead of silently splitting a dashboard in production.
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
        // counted from docs/03 §3.3 (45 rows, counting `/`-joined pairs as two codes)
        assertThat(EventCodes.ALL).hasSize(45)
        assertThat(EventCodes.ALL.toList().distinct()).hasSize(45)
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
