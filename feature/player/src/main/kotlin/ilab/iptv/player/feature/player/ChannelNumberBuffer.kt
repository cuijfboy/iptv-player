package ilab.iptv.player.feature.player

/**
 * The digit-input window of `docs/02 §8.2` ("`KEYCODE_0..9`=跳台（带 2 s 缓冲）").
 *
 * Pure and time-injected: the screen feeds it a digit key plus the current uptime and gets back what
 * to do — keep waiting, or commit the number. The window is what makes "1" then "2" mean channel 12
 * instead of channel 1 followed by channel 2, and the frozen value is [WINDOW_MS].
 *
 * `onTick` is the timer's side of that contract: the caller schedules one wake-up per window (and
 * re-schedules when a digit arrives), then asks the buffer whether the window is over. A third digit
 * commits immediately — no channel number in the catalog is longer than [MAX_DIGITS], so waiting
 * longer could only add a number that cannot exist.
 */
class ChannelNumberBuffer(
    private val windowMs: Long = WINDOW_MS,
    private val maxDigits: Int = MAX_DIGITS,
) {

    /** What the caller should do after a key or a timer tick. */
    sealed interface Decision {
        data object None : Decision

        /** Keep the digits on screen and wait: the window may still be extended. */
        data class Pending(val number: Int, val remainingMs: Long) : Decision

        /** Jump to `number` now. */
        data class Commit(val number: Int) : Decision
    }

    private var digits: String = ""
    private var lastDigitAtMs: Long = 0L

    val pendingDigits: String get() = digits

    fun onDigit(digit: Int, nowMs: Long): Decision {
        if (digit !in 0..9) return Decision.None
        digits = (digits + digit).take(maxDigits)
        lastDigitAtMs = nowMs
        val number = digits.toIntOrNull() ?: return Decision.None
        return if (digits.length >= maxDigits) {
            reset()
            Decision.Commit(number)
        } else {
            Decision.Pending(number, windowMs)
        }
    }

    /** The scheduled wake-up: commit once the window since the last digit has passed. */
    fun onTick(nowMs: Long): Decision {
        if (digits.isEmpty()) return Decision.None
        val elapsed = nowMs - lastDigitAtMs
        val number = digits.toIntOrNull()
        if (elapsed < windowMs) {
            return number?.let { Decision.Pending(it, windowMs - elapsed) } ?: Decision.None
        }
        reset()
        return number?.let { Decision.Commit(it) } ?: Decision.None
    }

    fun reset() {
        digits = ""
    }

    companion object {
        /** docs/02 §8.2: the frozen 2 s digit window. */
        const val WINDOW_MS = 2_000L

        /** Channel numbers are at most three digits (the catalog's own ceiling, see P1-2 §12). */
        const val MAX_DIGITS = 3
    }
}
