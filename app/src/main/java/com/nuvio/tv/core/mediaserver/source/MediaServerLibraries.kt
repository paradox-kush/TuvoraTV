package com.nuvio.tv.core.mediaserver.source

import com.nuvio.tv.core.mediaserver.client.mediabrowser.ItemDto

/**
 * Which of a server's top-level views Tuvora can browse: libraries of movies and shows (and mixed ones). Music,
 * photos, books, playlists, live TV and the like are not part of v1 (design 5.4 "Not in v1"), and a view the server
 * names without an id cannot be listed.
 */
internal object MediaServerLibraries {
    enum class Kind { MOVIES, SERIES, MIXED }

    data class Library(val id: String, val name: String, val kind: Kind)

    fun browsable(views: List<ItemDto>): List<Library> = views.mapNotNull { view ->
        val id = view.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val name = view.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val kind = when (view.collectionType?.lowercase()) {
            "movies" -> Kind.MOVIES
            "tvshows" -> Kind.SERIES
            null, "", "mixed" -> Kind.MIXED
            else -> return@mapNotNull null
        }
        Library(id, name, kind)
    }
}
