package com.nuvio.tv.core.iptv.match

/** One row destined for the `keys` table. */
internal data class IndexKeyRow(val key: String, val sid: Int)

/**
 * Every `keys` row for [items], ordered the way the table stores them.
 *
 * `keys` is declared `WITHOUT ROWID` with `PRIMARY KEY(provider, kind, k, sid)`, so the primary key
 * *is* the storage B-tree — there is no separate heap. `provider` and `kind` are constant within a
 * write, which leaves `k` (a normalised title) as the leading variable column. Feeding rows in
 * catalog order therefore lands each insert at an essentially random leaf: page splits, no write
 * locality, and a working set far larger than the page cache.
 *
 * Sorting by `(k, sid)` first makes the batch arrive in B-tree order, which is what SQLite is fast
 * at. That matters more here than on mobile: this index shares its database with the hub, and the
 * measured symptom on a 2 GB Onn 4K box was the hub's category reads queueing behind the writer
 * lock — movies reading as "not loading" until the build finished. Shorter, cheaper transactions
 * are exactly the fix for that.
 *
 * Ordering only: the set of (key, sid) pairs is exactly what [TitleNormalizer.keysOf] produced.
 * KMP twin of NuvioMobile's `sortedKeyRows`.
 */
internal fun sortedKeyRows(items: List<IndexedItem>): List<IndexKeyRow> =
    items.flatMap { item -> TitleNormalizer.keysOf(item.name).map { IndexKeyRow(it, item.sid) } }
        .sortedWith(compareBy({ it.key }, { it.sid }))
