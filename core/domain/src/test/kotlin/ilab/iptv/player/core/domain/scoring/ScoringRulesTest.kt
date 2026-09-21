package ilab.iptv.player.core.domain.scoring

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScoringRulesTest {

    private val availability = AvailabilityRule()
    private val stability = StabilityRule()
    private val device = DeviceCompatibilityRule()
    private val resolution = ResolutionRule()
    private val antiLeech = AntiLeechRule()
    private val segment = SegmentSuccessRule()

    @Test
    fun `availability is all or nothing on the deep probe`() {
        assertThat(availability.evaluate(scoreInput(probe = probe(passed = true)))).isEqualTo(35)
        assertThat(availability.evaluate(scoreInput(probe = probe(passed = false)))).isEqualTo(0)
    }

    @Test
    fun `stability scales with the health factor`() {
        assertThat(stability.evaluate(scoreInput(stability = 1.0))).isEqualTo(25)
        assertThat(stability.evaluate(scoreInput(stability = 0.5))).isEqualTo(13)
        assertThat(stability.evaluate(scoreInput(stability = 0.0))).isEqualTo(0)
    }

    @Test
    fun `a playlist that stopped advancing loses ten more`() {
        val stalled = scoreInput(stability = 1.0, probe = probe(evidence = mapOf("stalled" to true)))
        assertThat(stability.evaluate(stalled)).isEqualTo(15)
    }

    @Test
    fun `device compatibility follows the video family`() {
        assertThat(device.evaluate(scoreInput(videoCodec = "video/avc"))).isEqualTo(20)
        assertThat(device.evaluate(scoreInput(videoCodec = "video/hevc"))).isEqualTo(12)
        assertThat(device.evaluate(scoreInput(videoCodec = "video/av01"))).isEqualTo(4)
        assertThat(device.evaluate(scoreInput(videoCodec = null))).isEqualTo(12)
    }

    @Test
    fun `ac3 without passthrough costs five and above 1080p costs three`() {
        assertThat(device.evaluate(scoreInput(audioCodec = "audio/ac3"))).isEqualTo(15)
        assertThat(device.evaluate(scoreInput(width = 3840, height = 2160))).isEqualTo(17)
        val passthrough = DEVICE_1080P_NO_PASSTHROUGH.copy(audioPassthrough = setOf("ac3", "eac3"))
        assertThat(device.evaluate(scoreInput(audioCodec = "audio/ac3", device = passthrough))).isEqualTo(20)
    }

    @Test
    fun `resolution pays the three documented tiers and nothing for an unknown size`() {
        assertThat(resolution.evaluate(scoreInput(width = 1920, height = 1080))).isEqualTo(10)
        assertThat(resolution.evaluate(scoreInput(width = 1280, height = 720))).isEqualTo(8)
        assertThat(resolution.evaluate(scoreInput(width = 854, height = 480))).isEqualTo(5)
        assertThat(resolution.evaluate(scoreInput(width = 0, height = 0))).isEqualTo(0)
    }

    @Test
    fun `anti-leech pays less when the stream needs headers`() {
        assertThat(antiLeech.evaluate(scoreInput(stream = scoreStream()))).isEqualTo(5)
        assertThat(antiLeech.evaluate(scoreInput(stream = scoreStream(userAgent = "okhttp")))).isEqualTo(3)
        assertThat(antiLeech.evaluate(scoreInput(stream = scoreStream(referrer = "https://x.invalid")))).isEqualTo(3)
    }

    @Test
    fun `segment credit needs a 2xx segment in the evidence`() {
        assertThat(segment.evaluate(scoreInput(probe = probe(evidence = mapOf("segStatus" to 200))))).isEqualTo(2)
        assertThat(segment.evaluate(scoreInput(probe = probe(evidence = mapOf("segStatus" to 500))))).isEqualTo(0)
        assertThat(segment.evaluate(scoreInput())).isEqualTo(0)
    }

    @Test
    fun `weights are the document's and add up to 97`() {
        assertThat(ScoringWeights.AVAILABILITY).isEqualTo(35)
        assertThat(ScoringWeights.STABILITY).isEqualTo(25)
        assertThat(ScoringWeights.DEVICE_COMPAT).isEqualTo(20)
        assertThat(ScoringWeights.RESOLUTION).isEqualTo(10)
        assertThat(ScoringWeights.ANTI_LEECH).isEqualTo(5)
        assertThat(ScoringWeights.SEGMENT).isEqualTo(2)
        assertThat(ScoringWeights.TOTAL).isEqualTo(97)
    }
}
