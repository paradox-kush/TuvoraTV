package com.nuvio.tv.core.mediaserver.policy

/**
 * The matched lane's per-server match cache (design 5.6): "external id -> the server items that carry it", with a
 * TTL, so reopening a title page - and the in-player episode switch, which asks again for every episode - costs no
 * request after the first lookup (the Xtream lane re-ran its lookup on every switch). Positive answers live long (the
 * item is re-fetched at play anyway, and a deleted one drops the entry); a NEGATIVE answer ("not on this server")
 * lives only briefly, because the owner may add the title at any time and a Jellyfin title search can also miss a
 * retitled item. Bounded; pure of I/O so the TTL and eviction test with a fake clock.
 */
internal class MatchCache(
    private val positiveTtlMs: Long = POSITIVE_TTL_MS,
    private val negativeTtlMs: Long = NEGATIVE_TTL_MS,
    private val maxEntries: Int = MAX_ENTRIES,
) {
    data class Key(val serverKey: String, val kind: MatchLookupPolicy.ItemKind, val externalId: String)

    sealed interface Answer {
        data class Hit(val itemIds: List<String>) : Answer
        data object NotOnServer : Answer
        data object Unknown : Answer
    }

    private class Entry(val itemIds: List<String>, val storedAtMs: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<Key, Entry>()

    fun get(key: Key, nowMs: Long): Answer = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized Answer.Unknown
        val ttl = if (entry.itemIds.isEmpty()) negativeTtlMs else positiveTtlMs
        if (nowMs - entry.storedAtMs >= ttl) {
            entries.remove(key)
            return@synchronized Answer.Unknown
        }
        // most recently used goes last, so eviction drops the stalest
        entries.remove(key)
        entries[key] = entry
        if (entry.itemIds.isEmpty()) Answer.NotOnServer else Answer.Hit(entry.itemIds)
    }

    /** [itemIds] empty = a verified "not on this server". */
    fun put(key: Key, itemIds: List<String>, nowMs: Long) = synchronized(lock) {
        entries.remove(key)
        entries[key] = Entry(itemIds, nowMs)
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
    }

    fun invalidate(key: Key) {
        synchronized(lock) { entries.remove(key) }
    }

    fun forgetServer(serverKey: String) = synchronized(lock) { entries.keys.removeAll { it.serverKey == serverKey } }

    internal val sizeForTest: Int get() = synchronized(lock) { entries.size }

    companion object {
        const val POSITIVE_TTL_MS = 12 * 60 * 60 * 1000L
        const val NEGATIVE_TTL_MS = 10 * 60 * 1000L
        const val MAX_ENTRIES = 300

        /** The id a title is cached under: TMDB when known (stable across addons), else IMDb. */
        fun externalId(ids: MatchLookupPolicy.ExternalIds): String? =
            ids.tmdb?.takeIf { it.isNotBlank() }?.let { "tmdb.${it.trim()}" }
                ?: ids.imdb?.takeIf { it.isNotBlank() }?.let { "imdb.${it.trim().lowercase()}" }
                ?: ids.tvdb?.takeIf { it.isNotBlank() }?.let { "tvdb.${it.trim()}" }
    }
}
