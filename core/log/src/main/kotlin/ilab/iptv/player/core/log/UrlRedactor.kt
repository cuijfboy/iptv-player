package ilab.iptv.player.core.log

import ilab.iptv.player.core.common.Redactor

/**
 * Redacts what docs/03 §11 forbids in logs and export packages.
 *
 * Scope of this P0 skeleton: credentials in query parameters (`token`, `key`, `pwd`, …) and
 * userinfo in a URL (`scheme://user:pass@host`). The remaining §11 rules — replacing a URL path
 * with `scheme://host` + an 8-character path hash, and the `Redactor` re-run at the export
 * pipeline entry — land with the export package (L8 / P2), where a wrong path rendering can be
 * observed end-to-end. Applying the filter here already means an unredacted URL cannot reach the
 * ring buffer or logcat.
 */
class UrlRedactor : Redactor {

    override fun redact(url: String): String {
        if (url.isEmpty()) return url
        val withoutUserInfo = USER_INFO.replace(url, "$1***@")
        return SENSITIVE_PARAM.replace(withoutUserInfo) { match ->
            "${match.groupValues[1]}=$MASK"
        }
    }

    private companion object {
        const val MASK = "***"

        /** `scheme://user:pass@host` → `scheme://` + masked credentials + `@host`. */
        val USER_INFO = Regex("""([A-Za-z][A-Za-z0-9+.\-]*://)[^/\s@]+@""")

        /** docs/03 §11 sensitive keys, any case, anywhere in the string (query string or plain text). */
        val SENSITIVE_PARAM = Regex(
            """(?i)\b(token|key|pwd|pass|passwd|sign|auth|secret|expire)=([^&\s"']*)""",
        )
    }
}
