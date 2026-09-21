package ilab.iptv.player.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CodecIdsTest {

    @Test
    fun `codec attributes and mime names map to the same video family`() {
        assertThat(CodecIds.videoFamily("avc1.640028")).isEqualTo(CodecIds.VideoFamily.H264)
        assertThat(CodecIds.videoFamily("video/avc")).isEqualTo(CodecIds.VideoFamily.H264)
        assertThat(CodecIds.videoFamily("hvc1.1.6.L93.B0")).isEqualTo(CodecIds.VideoFamily.HEVC)
        assertThat(CodecIds.videoFamily("HEVC")).isEqualTo(CodecIds.VideoFamily.HEVC)
        assertThat(CodecIds.videoFamily("av01.0.05M.08")).isEqualTo(CodecIds.VideoFamily.AV1)
    }

    @Test
    fun `unknown and absent codecs are unknown, not guessed`() {
        assertThat(CodecIds.videoFamily(null)).isEqualTo(CodecIds.VideoFamily.UNKNOWN)
        assertThat(CodecIds.videoFamily("")).isEqualTo(CodecIds.VideoFamily.UNKNOWN)
        assertThat(CodecIds.videoFamily("video/mp2t")).isEqualTo(CodecIds.VideoFamily.OTHER)
        assertThat(CodecIds.audioFamily("mpeg4-generic")).isEqualTo(CodecIds.AudioFamily.OTHER)
    }

    @Test
    fun `audio families cover aac ac3 and eac3`() {
        assertThat(CodecIds.audioFamily("mp4a.40.2")).isEqualTo(CodecIds.AudioFamily.AAC)
        assertThat(CodecIds.audioFamily("ac-3")).isEqualTo(CodecIds.AudioFamily.AC3)
        assertThat(CodecIds.audioFamily("ec-3")).isEqualTo(CodecIds.AudioFamily.EAC3)
    }

    @Test
    fun `mime names are the ones the stream row stores`() {
        assertThat(CodecIds.videoMime("avc1.640028")).isEqualTo("video/avc")
        assertThat(CodecIds.videoMime("hvc1.1.6.L93.B0")).isEqualTo("video/hevc")
        assertThat(CodecIds.videoMime("av01.0.05M.08")).isEqualTo("video/av01")
        assertThat(CodecIds.audioMime("mp4a.40.2")).isEqualTo("audio/mp4a-latm")
        assertThat(CodecIds.audioMime("ac-3")).isEqualTo("audio/ac3")
        assertThat(CodecIds.videoMime("weird")).isNull()
    }
}
