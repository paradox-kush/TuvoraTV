package com.nuvio.tv.core.memory

/**
 * The single place that knows the app's total heap ambition (twin of the mobile/desktop
 * registry; a plain JVM lock replaces atomicfu here). Every in-memory cache is created
 * through here: a name, a tier-sized cap, and a trim callback. Anonymous caches (an
 * LruCache outside the registry) fail review. One pressure listener walks the registry
 * and trims — no per-class handlers.
 */
internal class BudgetRegistry {

    private class Member(val name: String, val capBytes: Long, val priority: Int, val trim: () -> Unit)

    private val lock = Any()
    private val members = mutableListOf<Member>()

    /**
     * Registers a cache. [priority] orders trimming: LOWER values are trimmed first
     * (0 = most expendable). Returns false — and keeps the existing registration —
     * when [name] is already taken.
     */
    fun register(name: String, capBytes: Long, priority: Int, trim: () -> Unit): Boolean =
        synchronized(lock) {
            if (members.any { it.name == name }) return@synchronized false
            members.add(Member(name, capBytes, priority, trim))
            true
        }

    /** The registered cap for [name], or null if unknown. */
    fun capOf(name: String): Long? = synchronized(lock) { members.firstOrNull { it.name == name }?.capBytes }

    /** Sum of every registered cap — the registry's total heap ambition. */
    val totalCapBytes: Long get() = synchronized(lock) { members.sumOf { it.capBytes } }

    /** Walks every member's trim callback in declared priority order (ties: registration order). */
    fun trimAll() {
        // Snapshot under the lock, call outside it: a trim callback must never deadlock
        // against a concurrent registration.
        val snapshot = synchronized(lock) { members.sortedBy { it.priority } }
        for (member in snapshot) member.trim()
    }
}
