package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.policy.MediaServerIds

/**
 * Maps an `ms:` content id back to what the direct lane needs to offer a stream (like `XtreamItemRegistry`):
 * the item's name/poster and its media sources, learned when a page or a row was built. A miss after a cold
 * start is rebuilt by [MediaServerMetaSource.ensureStreamRegistered] (one item fetch) - the in-memory map is a
 * cache, never the source of truth. NEVER holds a token or a playable URL: listing never calls PlaybackInfo,
 * and the stream a list shows is a deferred reference minted at play time (design 5.5).
 */
internal object MediaServerItemRegistry {
    /** A text subtitle that lives beside the file (not inside the container): the engines list embedded tracks themselves, these must be handed over. */
    data class SidecarSubtitle(val index: Int, val language: String, val label: String)

    data class Source(
        val id: String,
        val label: String,
        val container: String?,
        val subtitles: List<SidecarSubtitle> = emptyList(),
        /** The server's own words for this version (an add-on result's name and release), shown under [label]; null when they add nothing. */
        val description: String? = null,
    )

    data class Item(
        val contentId: String,
        val serverKey: String,
        val kind: MediaServerIds.Kind,
        val itemId: String,
        val name: String,
        val poster: String?,
        val sources: List<Source>,
        val durationMs: Long?,
        /** The server's own resume position (`UserData.PlaybackPositionTicks`) as of this fetch - for the "resume from server" offer (D3). */
        val serverPositionMs: Long?,
        val serverLastPlayedAt: String?,
        val sourcesLoaded: Boolean = false,
    )

    private val lock = Any()
    private val items = LinkedHashMap<String, Item>()

    /** The cache is bounded: browsing a huge library must not grow memory without limit (oldest entries go first; a miss is one fetch). */
    internal const val MAX_ITEMS = 6_000

    fun register(item: Item) = synchronized(lock) { put(item) }

    fun registerAll(batch: List<Item>) = synchronized(lock) { batch.forEach(::put) }

    private fun put(item: Item) {
        items.remove(item.contentId)
        items[item.contentId] = item
        while (items.size > MAX_ITEMS) items.remove(items.keys.first())
    }

    internal fun sizeForTest(): Int = synchronized(lock) { items.size }

    fun get(contentId: String): Item? = synchronized(lock) { items[contentId] }

    /** Drops everything - a profile switch must not leak another profile's items. */
    fun reset() = synchronized(lock) { items.clear() }

    /** Drops one server's items (it was removed or its user changed). */
    fun forget(serverKeyPrefix: String) = synchronized(lock) { items.keys.removeAll { it.startsWith("${MediaServerIds.CONTENT_PREFIX}:$serverKeyPrefix") } }
}
