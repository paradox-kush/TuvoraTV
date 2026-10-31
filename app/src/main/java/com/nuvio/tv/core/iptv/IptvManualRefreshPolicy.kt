package com.nuvio.tv.core.iptv

/**
 * UX179: "Refresh now" on a playlist. Pure decisions, mirrored from the KMP apps' `IptvManualRefreshPolicy`: what a
 * manual refresh DOES per source, how long before another may start (measured on a MONOTONIC clock), whether a rebuild
 * really landed (build time CHANGED, compared before vs after so a clock jump cannot fake or hide it), and the
 * "Updated N ago" wording. User-initiated, never a loop, with a cooldown so a held OK cannot hammer a provider.
 */
object IptvManualRefreshPolicy {
    /** One manual refresh per playlist per minute. */
    const val COOLDOWN_MS = 60_000L

    enum class Mode {
        /** Xtream / M3U URL / M3U file with a copy: rebuild the catalog now. */
        REFRESH_CATALOG,

        /** An M3U FILE playlist with no copy on this device: nothing to re-read here. */
        NEEDS_LOCAL_FILE,

        /** Stalker: a handshake can evict another device on the same MAC, so it is never refreshed in the background. */
        RELOAD_ON_OPEN,
    }

    fun modeFor(isStalker: Boolean, isFile: Boolean, hasLocalCopy: Boolean): Mode = when {
        isStalker -> Mode.RELOAD_ON_OPEN
        isFile && !hasLocalCopy -> Mode.NEEDS_LOCAL_FILE
        else -> Mode.REFRESH_CATALOG
    }

    /** Milliseconds until another manual refresh may start; 0 when it may start now ([sinceLastStartMs]: monotonic elapsed). */
    fun cooldownRemainingMs(sinceLastStartMs: Long?): Long {
        val elapsed = sinceLastStartMs ?: return 0L
        return (COOLDOWN_MS - elapsed.coerceAtLeast(0L)).coerceIn(0L, COOLDOWN_MS)
    }

    enum class AgeUnit { JUST_NOW, MINUTES, HOURS, DAYS }

    data class Age(val unit: AgeUnit, val count: Long)

    fun age(lastMs: Long?, nowMs: Long): Age? {
        if (lastMs == null || lastMs <= 0L) return null
        val minutes = ((nowMs - lastMs).coerceAtLeast(0L)) / 60_000L
        return when {
            minutes < 1L -> Age(AgeUnit.JUST_NOW, 0L)
            minutes < 60L -> Age(AgeUnit.MINUTES, minutes)
            minutes < 60L * 24L -> Age(AgeUnit.HOURS, minutes / 60L)
            else -> Age(AgeUnit.DAYS, minutes / (60L * 24L))
        }
    }

    enum class Landed { ALL, SOME, NONE }

    fun landed(builtAtBefore: List<Long?>, builtAtAfter: List<Long?>): Landed {
        if (builtAtAfter.isEmpty() || builtAtBefore.size != builtAtAfter.size) return Landed.NONE
        val changed = builtAtAfter.indices.count { i -> builtAtAfter[i] != null && builtAtAfter[i] != builtAtBefore[i] }
        return when (changed) {
            builtAtAfter.size -> Landed.ALL
            0 -> Landed.NONE
            else -> Landed.SOME
        }
    }
}

/** What a manual refresh ended as. [REFRESHED] is the only success. */
enum class ManualRefreshResult { REFRESHED, PARTIAL, FAILED, COOLDOWN, RELOAD_ON_OPEN, NEEDS_LOCAL_FILE }
