package ilab.iptv.player.feature.epg.grid

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgrammeIndexTest {

    private val base = 1_700_000_000_000L
    private val ten = base + 10 * HOUR_MS

    private val index = ProgrammeIndex.of(
        listOf(
            programme(3, "cctv1.cn", ten + 3 * HOUR_MS, 60, "Midday"),
            programme(1, "cctv1.cn", ten, 60, "Morning"),
            programme(2, "cctv1.cn", ten + HOUR_MS, 60, "Noon"),
        ),
    )

    @Test
    fun `lookup answers the programme covering the instant`() {
        assertThat(index.at(ten + 30 * MINUTE_MS)?.id).isEqualTo(1)
        // Start is inclusive, stop is exclusive: the boundary belongs to the next programme.
        assertThat(index.at(ten + HOUR_MS)?.id).isEqualTo(2)
        assertThat(index.at(ten + 2 * HOUR_MS - 1)?.id).isEqualTo(2)
    }

    @Test
    fun `lookup answers null in a gap or outside the guide`() {
        assertThat(index.at(ten + 2 * HOUR_MS)).isNull()
        assertThat(index.at(ten + 2 * HOUR_MS + 30 * MINUTE_MS)).isNull()
        assertThat(index.at(ten - HOUR_MS)).isNull()
        assertThat(index.at(ten + 5 * HOUR_MS)).isNull()
    }

    @Test
    fun `the index is sorted on construction, not by the caller`() {
        assertThat(index.programmes.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(index.firstStartMs()).isEqualTo(ten)
        assertThat(index.lastStopMs()).isEqualTo(ten + 4 * HOUR_MS)
    }

    @Test
    fun `overlapping slots answer with the most specific one`() {
        val overlapping = ProgrammeIndex.of(
            listOf(
                programme(10, "x", ten, 60, "Original"),
                programme(11, "x", ten + 30 * MINUTE_MS, 60, "Correction"),
            ),
        )
        assertThat(overlapping.at(ten + 45 * MINUTE_MS)?.id).isEqualTo(11)
        assertThat(overlapping.at(ten + 10 * MINUTE_MS)?.id).isEqualTo(10)
    }

    @Test
    fun `intersection is half-open on both ends`() {
        assertThat(index.intersecting(ten + 2 * HOUR_MS, ten + 3 * HOUR_MS)).isEmpty()
        assertThat(index.intersecting(ten + 30 * MINUTE_MS, ten + 90 * MINUTE_MS).map { it.id })
            .containsExactly(1L, 2L)
        assertThat(index.intersecting(ten + 90 * MINUTE_MS, ten + 3 * HOUR_MS + 1).map { it.id })
            .containsExactly(2L, 3L)
    }

    @Test
    fun `intersection includes a programme that started before the window`() {
        assertThat(index.intersecting(ten + 30 * MINUTE_MS, ten + 45 * MINUTE_MS).map { it.id })
            .containsExactly(1L)
    }

    @Test
    fun `an empty guide answers nulls and no intersections`() {
        val empty = ProgrammeIndex.empty
        assertThat(empty.isEmpty).isTrue()
        assertThat(empty.at(ten)).isNull()
        assertThat(empty.intersecting(ten, ten + HOUR_MS)).isEmpty()
        assertThat(empty.firstStartMs()).isNull()
        assertThat(empty.lastStopMs()).isNull()
    }

    @Test
    fun `a degenerate window intersects nothing`() {
        assertThat(index.intersecting(ten, ten)).isEmpty()
        assertThat(index.intersecting(ten + HOUR_MS, ten)).isEmpty()
    }
}
