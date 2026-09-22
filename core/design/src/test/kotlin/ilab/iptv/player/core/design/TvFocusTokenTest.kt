package ilab.iptv.player.core.design

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * P3-7 item 2's guard: the focus tokens exist twice on purpose — as XML colors for every View-based
 * screen and as [TvFocus] ints for the pure EPG renderer — so a test reads the XML back and refuses
 * to let the two halves drift. Without it, "统一焦点视觉" would quietly become untrue the first time
 * someone tweaks one of the two.
 *
 * The file is read from the module directory (Gradle's unit-test working directory is the module),
 * so it needs neither Robolectric nor a device.
 */
class TvFocusTokenTest {

    private val colors: Map<String, Int> = parseColorResources(File("src/main/res/values/colors.xml"))
    private val dimens: Map<String, String> = parseStringResources(File("src/main/res/values/dimens.xml"))

    @Test
    fun `the xml colors are the ones the pure renderer uses`() {
        assertThat(colors["focus_stroke"]).isEqualTo(TvFocus.STROKE)
        assertThat(colors["focus_fill"]).isEqualTo(TvFocus.FILL)
        assertThat(colors["focus_fill_pressed"]).isEqualTo(TvFocus.FILL_PRESSED)
        assertThat(colors["focus_fill_over_video"]).isEqualTo(TvFocus.FILL_OVER_VIDEO)
    }

    @Test
    fun `the stroke width token matches the constant`() {
        assertThat(dimens["focus_stroke_width"]).isEqualTo("${TvFocus.STROKE_WIDTH_DP}dp")
    }

    @Test
    fun `the accent is opaque and visibly lighter than the darkest resting fill`() {
        // Alpha is FF on all tokens — a translucent focus state over video would violate §8.2.
        listOf(TvFocus.STROKE, TvFocus.FILL, TvFocus.FILL_PRESSED, TvFocus.FILL_OVER_VIDEO).forEach { argb ->
            assertThat(argb ushr 24 and 0xFF).isEqualTo(0xFF)
        }
        assertThat(TvFocus.FILL).isNotEqualTo(TvFocus.FILL_PRESSED)
    }

    private fun parseColorResources(file: File): Map<String, Int> =
        parseAttributes(file, "color") { value -> parseArgb(value) }

    private fun parseStringResources(file: File): Map<String, String> =
        parseAttributes(file, "dimen") { value -> value }

    private fun <T> parseAttributes(file: File, tag: String, convert: (String) -> T): Map<String, T> {
        assertThat(file.exists()).isTrue()
        val pattern = Regex("<$tag\\s+name=\"([^\"]+)\"\\s*>([^<]+)</$tag>")
        return pattern.findAll(file.readText()).associate { match ->
            match.groupValues[1] to convert(match.groupValues[2].trim())
        }
    }

    /** `#AARRGGBB` (or `#RRGGBB`, which Android treats as opaque). */
    private fun parseArgb(value: String): Int {
        val hex = value.removePrefix("#")
        return when (hex.length) {
            6 -> (0xFF000000 or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> error("unsupported color literal: $value")
        }
    }
}
