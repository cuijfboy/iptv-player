package ilab.iptv.player.core.log

import ilab.iptv.player.core.common.SessionIdFactory
import java.util.UUID

/**
 * Session ids shaped like the docs/03 §4 examples (`play-7f3a`, `refresh-2c11`): a caller-supplied
 * prefix plus 4 hex characters. Not a secret, just enough to fold one run together.
 */
class UuidSessionIdFactory : SessionIdFactory {
    override fun newId(prefix: String): String {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(SUFFIX_LENGTH)
        return if (prefix.isBlank()) suffix else "$prefix-$suffix"
    }

    private companion object {
        const val SUFFIX_LENGTH = 4
    }
}
