package com.nuvio.tv.core.telemetry

/**
 * Names a failure by a class that survives R8. A release build renames our own and library classes
 * ("u7"), which made `iptv_index_build.detail` unreadable. Walk up to the nearest JDK/Kotlin ancestor,
 * whose name is stable. Never includes message text.
 */
object ErrorClassPolicy {
    private val stable = listOf("java.", "javax.", "kotlin.", "kotlinx.", "android.")
    private val stableLibs = listOf("okhttp3.", "com.squareup.moshi.", "retrofit2.")

    fun of(t: Throwable): String {
        var c: Class<*>? = t.javaClass
        while (c != null) {
            val n = c.name
            if (stable.any(n::startsWith) || stableLibs.any(n::startsWith)) return c.simpleName.ifEmpty { "Throwable" }
            c = c.superclass
        }
        return "Throwable"
    }
}
