package ilab.iptv.player.core.source.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * The text of one decoded playlist plus what we had to do to get it.
 *
 * [fallbackFromUtf8] is the signal the caller turns into the `NET_CHARSET_FALLBACK` event
 * (docs/03 §3.3: "UTF-8 → GB18030 回退"); [lossy] means even the fallback charset could not decode
 * every byte and replacement characters were substituted, so the text is best-effort.
 */
data class DecodedText(
    val text: String,
    val charset: String,
    val fallbackFromUtf8: Boolean,
    val lossy: Boolean,
)

/**
 * Decodes playlist bytes defensively (docs/02 §6.1 Fetch: "UTF-8 → GB18030 回退").
 *
 * Order of attempts:
 *  1. an explicit [CharsetTextDecoder.decode] hint, if the caller has one;
 *  2. a byte-order mark (BOM): UTF-8 / UTF-16LE / UTF-16BE, which is authoritative;
 *  3. strict UTF-8;
 *  4. strict GB18030 (the mainland-Chinese default the pipeline names) → [DecodedText.fallbackFromUtf8];
 *  5. GB18030 with replacement, so a few bad bytes can never lose the whole playlist
 *     ([DecodedText.lossy]).
 *
 * "Strict" means a malformed/unmappable byte is reported instead of being silently turned into
 * U+FFFD — `String(bytes, charset)` does the silent thing and must not be used here.
 */
object TextDecoder {

    private const val UTF8 = "UTF-8"
    private const val GB18030 = "GB18030"

    /** BOM bytes, longest first so UTF-32LE cannot be mistaken for UTF-16LE. */
    private val BOMS: List<Pair<ByteArray, String>> = listOf(
        hex("0000FEFF") to "UTF-32BE",
        hex("FFFE0000") to "UTF-32LE",
        hex("EFBBBF") to "UTF-8",
        hex("FEFF") to "UTF-16BE",
        hex("FFFE") to "UTF-16LE",
    )

    fun decode(bytes: ByteArray, charsetHint: String? = null): DecodedText {
        if (bytes.isEmpty()) return DecodedText("", UTF8, fallbackFromUtf8 = false, lossy = false)

        charsetHint?.let { hint ->
            val charset = runCatching { Charset.forName(hint) }.getOrNull()
            if (charset != null) {
                val strict = decodeStrict(bytes, charset)
                if (strict != null) {
                    return DecodedText(stripBom(strict), charset.name(), fallbackFromUtf8 = false, lossy = false)
                }
            }
        }

        BOMS.firstOrNull { (bom, _) -> bytes.startsWith(bom) }?.let { (bom, name) ->
            safeCharset(name)?.let { charset ->
                val strict = decodeStrict(bytes, charset)
                if (strict != null) {
                    return DecodedText(stripBom(strict), charset.name(), fallbackFromUtf8 = false, lossy = false)
                }
            }
        }

        decodeStrict(bytes, Charsets.UTF_8)?.let {
            return DecodedText(stripBom(it), UTF8, fallbackFromUtf8 = false, lossy = false)
        }

        val gb18030 = safeCharset(GB18030) ?: Charsets.ISO_8859_1
        decodeStrict(bytes, gb18030)?.let {
            return DecodedText(stripBom(it), gb18030.name(), fallbackFromUtf8 = true, lossy = false)
        }

        val replaced = gb18030.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        return DecodedText(stripBom(replaced), gb18030.name(), fallbackFromUtf8 = true, lossy = true)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Drops a leading U+FEFF so a BOM never becomes the first character of a name or URL. */
    private fun stripBom(text: String): String =
        if (text.startsWith('\uFEFF')) text.substring(1) else text

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    private fun hex(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) or Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    /** True when [name] is a charset this process can actually decode with. */
    fun isSupported(name: String): Boolean =
        safeCharset(name) != null

    /** Charset lookup that returns null instead of throwing for an unknown name. */
    private fun safeCharset(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()
}
