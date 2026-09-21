package ilab.iptv.player.core.source.parser

import ilab.iptv.player.core.common.AppError
import ilab.iptv.player.core.common.AppResult
import ilab.iptv.player.core.common.EventCodes
import ilab.iptv.player.core.source.text.TextDecoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * Entry point for the Parse stage (docs/02 §6.1): bytes in, [ParseOutcome] out.
 *
 * [parse] (bytes) is the one a `SourceProvider` calls: it runs the charset fallback first, then
 * picks the dialect, then parses. Failures travel as [AppResult.Err] — never an exception — per the
 * docs/02 §4.0 F2 rule "异常不跨边界"; [CancellationException] is the one exception and is rethrown.
 */
object PlaylistParsers {

    /** True when a failure code is one of the registered event codes (guards against literals). */
    private val parseFailureCode = EventCodes.SRC_FETCH_FAIL

    fun parse(
        bytes: ByteArray,
        sourceId: String,
        charsetHint: String? = null,
        format: PlaylistFormat? = null,
    ): AppResult<ParseOutcome> = try {
        val decoded = TextDecoder.decode(bytes, charsetHint)
        val resolved = format ?: detectFormat(decoded.text)
        val outcome = parse(decoded.text, sourceId, resolved)
        AppResult.Ok(
            outcome.copy(
                charset = decoded.charset,
                fallbackFromUtf8 = decoded.fallbackFromUtf8,
                lossyDecode = decoded.lossy,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Err(AppError.parse(parseFailureCode, e))
    }

    /** Parses already-decoded text; [format] defaults to detection when null. */
    fun parse(text: String, sourceId: String, format: PlaylistFormat? = null): ParseOutcome =
        when (format ?: detectFormat(text)) {
            PlaylistFormat.M3U -> M3uParser.parse(text, sourceId)
            PlaylistFormat.TXT -> TxtParser.parse(text, sourceId)
        }

    /**
     * M3U when the list carries an `#EXTM3U` header or an `#EXTINF` row, otherwise TXT. The
     * `#EXTINF` probe only reads the head of the file so a huge list is not scanned twice for
     * nothing.
     */
    fun detectFormat(text: String): PlaylistFormat {
        if (M3uParser.hasHeader(text)) return PlaylistFormat.M3U
        var probed = 0
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.regionMatches(0, "#EXTINF", 0, 7, ignoreCase = true)) return PlaylistFormat.M3U
            if (++probed >= DETECT_PROBE_LINES) break
        }
        return PlaylistFormat.TXT
    }

    private const val DETECT_PROBE_LINES = 50
}
