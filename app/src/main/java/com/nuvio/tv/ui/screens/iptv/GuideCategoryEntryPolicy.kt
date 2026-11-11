package com.nuvio.tv.ui.screens.iptv

/**
 * Where D-pad focus lands in the category column when the viewer enters it with no remembered row
 * (re-entering the Live TV tab). Focus-follows-selection means whichever row takes focus becomes the
 * shown category, so landing on row 0 silently selected Search (B238).
 */
internal object GuideCategoryEntryPolicy {
    /** The selected category; else the first non-Search one; else 0. */
    fun entryIndex(categoryIds: List<String>, selectedId: String?): Int {
        val selected = categoryIds.indexOf(selectedId)
        if (selectedId != null && selected >= 0) return selected
        val firstReal = categoryIds.indexOfFirst { it != XtreamLiveGuideViewModel.SEARCH_ID }
        return if (firstReal >= 0) firstReal else 0
    }
}

/** Which message the preview pane shows when the focused channel has no guide data. */
internal object GuideEmptyMessagePolicy {
    enum class Kind { FAVORITES_EMPTY, SEARCH_HINT, EMPTY_WINDOW, DATA_UNAVAILABLE }

    fun kind(awayFromNow: Boolean, selectedCategoryId: String?, channelsEmpty: Boolean): Kind = when {
        channelsEmpty && (selectedCategoryId == "__fav" || selectedCategoryId == "__allfav") -> Kind.FAVORITES_EMPTY
        channelsEmpty && selectedCategoryId == XtreamLiveGuideViewModel.SEARCH_ID -> Kind.SEARCH_HINT
        awayFromNow -> Kind.EMPTY_WINDOW
        else -> Kind.DATA_UNAVAILABLE
    }
}
