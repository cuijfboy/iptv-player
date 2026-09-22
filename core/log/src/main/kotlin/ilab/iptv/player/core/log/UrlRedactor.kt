package ilab.iptv.player.core.log

import ilab.iptv.player.core.common.Redactor
import java.security.MessageDigest

/**
 * Redacts what docs/03 §11 forbids in logs and export packages.
 *
 * The three §11 rules that apply to a URL, in this order:
 *
 * 1. **userinfo** — `scheme://user:pass@host` loses the credentials (masked + `@host`);
 * 2. **query parameters** — `token`/`key`/`pwd`/`pass`/`sign`/`auth`/`secret`/`expire` lose their
 *    value (replaced by the mask, any case, anywhere in the string);
 * 3. **path** — the path is replaced by its 8-character hash (`scheme://host/<hash>`), because the
 *    path is where a portal puts the account and the stream key
 *    (`/live/<user>/<key>/1.m3u8`). The full URL never leaves memory.
 *
 * Rule 3 landed with the **export package** (L8, P2-8) — it is the rule that makes the exported zip
 * safe to hand to someone else, and it is the "库内原文 / 导出脱敏" boundary of §11/W7. It is
 * **idempotent**: a path that already *is* an 8-character hex hash is left alone, so the export
 * pipeline's second pass over an already-redacted line changes nothing and the line a supporter
 * reads in the package is the line the console showed (see [HASH_SHAPED] for the one trade-off).
 *
 * The module masks **any** URL it sees, not just a caller-declared one: redaction is the single
 * entry, so a caller cannot "forget" to redact (docs/03 §11).
 */
class UrlRedactor : Redactor {

    override fun redact(url: String): String {
        if (url.isEmpty()) return url
        val withoutUserInfo = USER_INFO.replace(url, "$1***@")
        val withoutSecrets = SENSITIVE_PARAM.replace(withoutUserInfo) { match ->
            "${match.groupValues[1]}=$MASK"
        }
        return URL_PATH.replace(withoutSecrets) { match ->
            match.groupValues[1] + match.groupValues[2] + maskPath(match.groupValues[3])
        }
    }

    /** `path` keeps its leading `/`; the 8 hex characters are the first 8 of `SHA-256(path)`. */
    private fun maskPath(path: String): String =
        if (path.length <= 1 || HASH_SHAPED.matches(path)) path else "/" + pathHash(path)

    private fun pathHash(path: String): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM).digest(path.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(HASH_HEX_CHARS)
        digest.take(HASH_HEX_CHARS / 2).forEach { byte ->
            val value = byte.toInt() and 0xFF
            hex.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return hex.toString()
    }

    private companion object {
        const val MASK = "***"

        /** docs/03 §11 "路径的 hash 前 8 位". */
        const val HASH_ALGORITHM = "SHA-256"
        const val HASH_HEX_CHARS = 8
        const val HEX_DIGITS = "0123456789abcdef"

        /** `scheme://user:pass@host` → `scheme://` + masked credentials + `@host`. */
        val USER_INFO = Regex("""([A-Za-z][A-Za-z0-9+.\-]*://)[^/\s@]+@""")

        /** docs/03 §11 sensitive keys, any case, anywhere in the string (query string or plain text). */
        val SENSITIVE_PARAM = Regex(
            """(?i)\b(token|key|pwd|pass|passwd|sign|auth|secret|expire)=([^&\s"']*)""",
        )

        /**
         * `scheme://host` + a path. The path stops at the query/fragment/whitespace or a quote, so
         * the masked query parameters written by the step above are left exactly as they are.
         */
        val URL_PATH = Regex(
            """([A-Za-z][A-Za-z0-9+.\-]*://)([^/\s?#"'<>]+)(/[^\s?#"'<>]*)""",
        )

        /**
         * What a masked path already looks like. Leaving it alone is what makes rule 3 idempotent
         * (the export pipeline runs the redactor over lines the bus already redacted). The trade-off
         * is explicit: a real path that happens to be exactly 8 lowercase hex characters is left
         * unhashed — it is indistinguishable from a hash, so it discloses nothing a masked path does
         * not.
         */
        val HASH_SHAPED = Regex("""/[0-9a-f]{8}""")
    }
}
