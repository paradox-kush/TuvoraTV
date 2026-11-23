package com.nuvio.tv.core.radar

/**
 * "Hide scores + score delay" settings (per profile, synced via [RadarPrefs]). Defaults are today's
 * behaviour. Twin of the Mobile/Desktop class of the same name.
 */
data class ScoreSpoilerSettings(
    val shown: Boolean = true,
    val delaySeconds: Int = 0,
) {
    /** Delay in ms, sanitised (anything not in [ALLOWED_DELAYS] reads as 0). */
    val delayMs: Long get() = sanitizeDelay(delaySeconds) * 1000L

    companion object {
        val ALLOWED_DELAYS: List<Int> = listOf(0, 15, 30, 60)

        fun sanitizeDelay(seconds: Int): Int = if (seconds in ALLOWED_DELAYS) seconds else 0

        /** Chip cycle: 0 -> 15 -> 30 -> 60 -> 0. An unknown value restarts the cycle at 15. */
        fun nextDelay(current: Int): Int {
            val i = ALLOWED_DELAYS.indexOf(current)
            return if (i < 0) ALLOWED_DELAYS[1] else ALLOWED_DELAYS[(i + 1) % ALLOWED_DELAYS.size]
        }
    }
}
