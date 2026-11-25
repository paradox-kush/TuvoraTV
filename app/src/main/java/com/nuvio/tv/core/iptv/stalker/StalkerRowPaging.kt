package com.nuvio.tv.core.iptv.stalker

/**
 * B294: paging maths for a hub poster row on a Stalker portal, one `get_ordered_list` page per window.
 * Pure so the edges are pinned without a portal. Twin: NuvioMobile `StalkerRowPaging`.
 *
 * The row used to page SEQUENTIALLY up to 70 items (5 requests at 14 rows a page) before showing
 * anything; the session allows only a couple of concurrent requests and several rows load at once,
 * so the row showed no progress past its deadline and timed out although every page answered.
 */
object StalkerRowPaging {
    /** Rows a Stalker page carries when the portal has not said otherwise. */
    const val DEFAULT_PAGE_SIZE = 14

    /** The 1-based portal page that starts at raw [offset] (0 -> 1, n * pageSize -> n + 1). */
    fun pageFor(offset: Int, pageSize: Int): Int =
        if (pageSize <= 0 || offset <= 0) 1 else offset / pageSize + 1

    /** More pages exist: the portal's own total when it gave one, else "this page was full". */
    fun hasMore(page: Int, pageSize: Int, rowsOnPage: Int, totalItems: Int?): Boolean = when {
        rowsOnPage <= 0 -> false
        totalItems != null -> page.toLong() * pageSize < totalItems
        else -> rowsOnPage >= pageSize
    }

    /** The portal's page size: its `max_page_items` when positive, else what page 1 returned, else 14. */
    fun pageSizeOf(maxPageItems: Int?, rowsOnFirstPage: Int): Int = when {
        maxPageItems != null && maxPageItems > 0 -> maxPageItems
        rowsOnFirstPage > 0 -> rowsOnFirstPage
        else -> DEFAULT_PAGE_SIZE
    }
}

/** One portal page for a hub row: [items] as shown, [consumed] = RAW rows on the page (next offset = offset + consumed). */
data class StalkerRowWindow<T>(val items: List<T>, val hasMore: Boolean, val consumed: Int)
