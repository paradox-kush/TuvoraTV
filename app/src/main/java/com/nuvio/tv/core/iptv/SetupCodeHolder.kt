package com.nuvio.tv.core.iptv

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Step 2 — the ONE place a setup code lives between sign-in and the preview (decision 6.1): in memory
 * only. Never persisted, never logged, never a saved navigation argument, never in analytics. It is
 * cleared on success, on Cancel, and 30 minutes after it was set (a process death clears it too, which
 * is the point).
 */
@Singleton
class SetupCodeHolder(private val now: () -> Long) {
    @Inject constructor() : this({ System.currentTimeMillis() })

    private var code: String? = null
    private var setAt: Long = 0L

    /** Stores the NORMALIZED 12-character code (a malformed one is refused and clears the holder). */
    @Synchronized
    fun set(input: String): Boolean {
        val normalized = SetupCode.parse(input)
        if (normalized == null) {
            code = null
            return false
        }
        code = normalized
        setAt = now()
        return true
    }

    @Synchronized
    fun get(): String? {
        val c = code ?: return null
        if (now() - setAt >= TTL_MS) {
            code = null
            return null
        }
        return c
    }

    @Synchronized
    fun clear() {
        code = null
    }

    companion object {
        const val TTL_MS = 30L * 60_000L
    }
}
