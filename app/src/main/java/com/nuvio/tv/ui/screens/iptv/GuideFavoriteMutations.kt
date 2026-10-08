package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.first

/** Local live-library mutations; the guide owner serializes calls and publishes notices after commit. */
internal class GuideFavoriteMutations(
    private val library: LibraryRepository,
    private val profileId: () -> Int,
) {
    suspend fun toggle(channel: GuideChannel): FavoriteNotice {
        val profile = profileId()
        val previous = library.libraryItems.first().firstOrNull { it.id == channel.contentId && it.type == "tv" }
        library.toggleDefault(input(channel))
        return FavoriteNotice(channel, added = previous == null, previousSavedAt = previous?.listedAt, profileId = profile)
    }

    suspend fun undo(notice: FavoriteNotice): Boolean {
        if (notice.profileId != profileId()) return false
        val saved = library.libraryItems.first().any { it.id == notice.channel.contentId && it.type == "tv" }
        if (saved != notice.added) return false
        library.toggleDefault(input(notice.channel))
        if (notice.profileId != profileId()) return false
        if (!notice.added) {
            notice.previousSavedAt?.let { library.setFavoritesOrder(mapOf(notice.channel.contentId to it)) }
        }
        return true
    }

    private fun input(channel: GuideChannel) = LibraryEntryInput(
        itemId = channel.contentId, itemType = "tv", title = channel.name,
        poster = channel.logo, posterShape = PosterShape.LANDSCAPE, logo = channel.logo,
    )
}
