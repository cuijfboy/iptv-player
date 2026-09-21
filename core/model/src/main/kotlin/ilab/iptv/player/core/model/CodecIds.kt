package ilab.iptv.player.core.model

/**
 * Codec identifier normalization (docs/02 §6.1 设备兼容 dimension).
 *
 * Two vocabularies meet here and in the player:
 * - **RFC 6381 codec attributes** as they appear in an HLS `#EXT-X-STREAM-INF:CODECS=` list
 *   (`avc1.640028`, `hvc1.1.6.L93.B0`, `mp4a.40.2`, `ec-3`);
 * - **Android/Media3 MIME names** as they appear in `MediaFormat` and in `stream.vcodec` /
 *   `stream.acodec` (`video/avc`, `video/hevc`, `audio/mp4a-latm`, `audio/eac3`) — the names P1-3's
 *   `PLAY_FIRST_FRAME` reports and P1-4's quality label reads.
 *
 * Everything is pure string work, so both the deep probe (`:core:source`, declared codecs) and the
 * scorer (`:core:domain`, family → points) share one table instead of two.
 */
object CodecIds {

    /** The video families the §6.1 device-compatibility rule distinguishes. */
    enum class VideoFamily { H264, HEVC, AV1, OTHER, UNKNOWN }

    /** The audio families the §6.1 device-compatibility rule cares about (AC3/EAC3 lose points). */
    enum class AudioFamily { AAC, AC3, EAC3, OTHER, UNKNOWN }

    /** Maps either vocabulary (codec attribute or MIME) to a video family. */
    fun videoFamily(codec: String?): VideoFamily = when (normalized(codec)) {
        null -> VideoFamily.UNKNOWN
        "avc", "avc1", "avc3", "h264", "video/avc" -> VideoFamily.H264
        "hvc", "hvc1", "hev1", "hevc", "h265", "video/hevc" -> VideoFamily.HEVC
        "av01", "av1", "video/av01" -> VideoFamily.AV1
        else -> VideoFamily.OTHER
    }

    /** Maps either vocabulary to an audio family. */
    fun audioFamily(codec: String?): AudioFamily = when (normalized(codec)) {
        null -> AudioFamily.UNKNOWN
        "mp4a", "aac", "audio/mp4a-latm", "audio/aac" -> AudioFamily.AAC
        "ac-3", "ac3", "audio/ac3" -> AudioFamily.AC3
        "ec-3", "eac3", "audio/eac3" -> AudioFamily.EAC3
        else -> AudioFamily.OTHER
    }

    /**
     * Normalizes a codec token to the MIME name stored in the database, or null when the token is
     * not one we recognize. Recognized tokens are `CODECS=` attributes (`avc1.640028`) and MIME
     * names (`video/avc`); the parameter/version suffix is dropped.
     */
    fun videoMime(codec: String?): String? = when (videoFamily(codec)) {
        VideoFamily.H264 -> "video/avc"
        VideoFamily.HEVC -> "video/hevc"
        VideoFamily.AV1 -> "video/av01"
        VideoFamily.OTHER, VideoFamily.UNKNOWN -> null
    }

    /** Same as [videoMime] for audio; unknown tokens map to null rather than to a guess. */
    fun audioMime(codec: String?): String? = when (audioFamily(codec)) {
        AudioFamily.AAC -> "audio/mp4a-latm"
        AudioFamily.AC3 -> "audio/ac3"
        AudioFamily.EAC3 -> "audio/eac3"
        AudioFamily.OTHER, AudioFamily.UNKNOWN -> null
    }

    private fun normalized(codec: String?): String? {
        val trimmed = codec?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.contains('/')) return trimmed
        return trimmed.substringBefore('.')
    }
}
