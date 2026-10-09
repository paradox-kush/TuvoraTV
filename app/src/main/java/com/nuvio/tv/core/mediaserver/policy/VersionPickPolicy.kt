package com.nuvio.tv.core.mediaserver.policy

/**
 * The versions of one title on a server whose library is filled on demand (design 17): which id a pick asks for, and
 * what describes each version in the list.
 *
 * Such a server stamps the FIRST version with the item's own id (so a client that never picks still plays) and treats
 * that id as "choose for me" when the stream is requested - by its own preference order, which is not the order of the
 * list it answered. Recorded on a real server: picking the listed first version ("1080p BluRay 12 GB") streamed another
 * one (the 2160p REMUX). The version's own id is in its path (`/remux/{uuid}`, `/remux/{uuid}/{file}`); asking for that id returns exactly
 * that version. A plain Jellyfin library also gives its first version the item id, but there it IS that version - so the
 * id is swapped only when the path carries the version's own id.
 */
internal object VersionPickPolicy {
    private val OWN_ID_PATH = Regex("""^/remux/([0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12})(?:[/.]|$)""")

    /** The MediaSourceId to ask for when the viewer picks the version [sourceId] of item [itemId]. */
    fun pickId(itemId: String, sourceId: String, path: String?): String {
        if (!sameId(sourceId, itemId)) return sourceId
        val own = path?.let { OWN_ID_PATH.find(it) }?.groupValues?.get(1) ?: return sourceId
        return own.replace("-", "").lowercase()
    }

    /**
     * The line under a version's technical [label]: the server's own name for it when that name describes a prepared
     * stream ("Server A\n1080p\nBluRay x264 12 GB" - several lines: where it comes from, quality, release). Two versions
     * that probe alike ("1080p · H.264") are told apart only by it. A one-line name is a file name ("The Matrix (1999)")
     * and adds nothing; a name the label already shows is not repeated.
     */
    fun description(serverName: String?, label: String): String? {
        val lines = serverName?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        if (lines.size < 2) return null
        return lines.joinToString(" · ").takeUnless { it.equals(label, ignoreCase = true) }
    }

    /**
     * A stand-in the server lists in place of versions it has not prepared ("Streams resolve on play", "Load versions") or
     * found ("No streams found") - Jellyfin's `MediaSourceType.Placeholder`. Never a version to offer: picking one plays a
     * "nothing here" card or nothing at all.
     */
    fun isPlaceholder(type: String?): Boolean = type.equals("Placeholder", ignoreCase = true)

    private fun sameId(a: String, b: String) = a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)
}
