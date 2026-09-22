package ilab.iptv.player.core.player

import com.google.common.truth.Truth.assertThat
import ilab.iptv.player.core.model.AudioTrackInfo
import ilab.iptv.player.core.model.SubtitleTrackInfo
import org.junit.Test

/** docs/04 P3-3 items 1–2: the audio/subtitle selection state machines. */
class TrackMenusTest {

    private val chinese = AudioTrackInfo(id = "audio:0:0", label = "中文", language = "zh", codec = "audio/ac3", isDefault = true)
    private val english = AudioTrackInfo(id = "audio:0:1", label = "English", language = "en", codec = "audio/ac3", isDefault = false)

    @Test
    fun `a single audio track offers no switch`() {
        val state = AudioTrackState(listOf(chinese), selectedId = "audio:0:0")
        assertThat(state.enabled).isFalse()
        assertThat(state.valueLabel()).isEqualTo("1 条")
        assertThat(state.options()).hasSize(1)
    }

    @Test
    fun `audio selection wraps and reports the chosen label`() {
        val state = AudioTrackState(listOf(chinese, english), selectedId = chinese.id)
        val afterOne = state.move(+1)
        assertThat(afterOne.selectedId).isEqualTo(english.id)
        assertThat(afterOne.valueLabel()).isEqualTo("English")
        // Up from the last entry wraps to the first: two entries do not deserve a dead end.
        assertThat(afterOne.move(+1).selectedId).isEqualTo(chinese.id)
        assertThat(state.move(-1).selectedId).isEqualTo(english.id)
    }

    @Test
    fun `audio options mark exactly the effective selection`() {
        // The engine reported no selection yet: the menu must still have a sensible highlighted row.
        val state = AudioTrackState(listOf(chinese, english), selectedId = null)
        assertThat(state.effectiveSelectedId()).isEqualTo(chinese.id)
        assertThat(state.options().count { it.selected }).isEqualTo(1)
        assertThat(state.select("audio:unknown")).isSameInstanceAs(state)
    }

    @Test
    fun `subtitle ladder starts with off and wraps back to it`() {
        val track = SubtitleTrackInfo(id = "text:0:0", label = "中文", language = "zh", mimeType = "application/x-subrip", isSelected = false)
        val state = SubtitleTrackState(listOf(track), selectedId = null, enabled = false)
        assertThat(state.available).isTrue()
        assertThat(state.valueLabel()).isEqualTo("关")
        assertThat(state.options().first().label).isEqualTo("关闭")

        val on = state.move(+1)
        assertThat(on.enabled).isTrue()
        assertThat(on.selectedId).isEqualTo(track.id)
        assertThat(on.valueLabel()).isEqualTo("中文")

        val offAgain = on.move(+1)
        assertThat(offAgain.enabled).isFalse()
        assertThat(offAgain.valueLabel()).isEqualTo("关")
    }

    @Test
    fun `a stream without subtitle tracks says so instead of offering 关闭`() {
        val state = SubtitleTrackState(emptyList(), selectedId = null, enabled = false)
        assertThat(state.available).isFalse()
        assertThat(state.valueLabel()).isEqualTo("无")
        // No track: moving and selecting change nothing (the entry point is greyed by the screen).
        assertThat(state.move(+1)).isSameInstanceAs(state)
        assertThat(state.select("text:9:9")).isSameInstanceAs(state)
    }

    @Test
    fun `selecting a known subtitle track turns subtitles on and 关闭 turns them off`() {
        val track = SubtitleTrackInfo(id = "text:0:0", label = "中文", language = "zh", mimeType = "text/vtt", isSelected = false)
        val state = SubtitleTrackState(listOf(track), selectedId = null, enabled = false)
        val selected = state.select(track.id)
        assertThat(selected.enabled).isTrue()
        assertThat(selected.selectedId).isEqualTo(track.id)
        val off = selected.select(null)
        assertThat(off.enabled).isFalse()
        assertThat(off.selectedId).isEqualTo(track.id)
    }
}
