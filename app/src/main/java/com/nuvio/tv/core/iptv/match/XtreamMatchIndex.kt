package com.nuvio.tv.core.iptv.match

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class MatchKind(val slug: String) { MOVIE("movie"), SERIES("series"), LIVE("live") }

/**
 * One catalog entry as stored in the index. [ext] = container extension (movies only).
 * P7 (items 4-5): the index doubles as the Xtream BROWSE catalog — [categoryId] scopes hub rows,
 * [epgId]/[hasArchive] carry the live-channel fields the guide needs.
 */
data class IndexedItem(
    val sid: Int, val name: String, val year: Int?, val tmdb: Int?, val ext: String?,
    val poster: String? = null, val categoryId: String? = null, val epgId: String? = null,
    val hasArchive: Boolean = false,
    /** Arrival index in the panel's bulk list — categories serve in THE PANEL'S order, never sorted. */
    val pos: Int = 0,
)

/** A confirmed (or confirmed-absent when [sid] is null) TMDB->stream mapping. */
data class CachedMapping(val sid: Int?, val matchedName: String?, val updatedAtMs: Long)

/** Outcome of a [XtreamMatchIndex.sync]: how much of the catalog actually changed. */
data class SyncStats(val added: Int, val changed: Int, val removed: Int, val total: Int)

/** Pure diff outcome: items to (re-)insert, sids whose old name-keys must be dropped, vanished sids. */
internal data class CatalogDiff(val upserts: List<IndexedItem>, val changedSids: List<Int>, val goneSids: List<Int>)

/**
 * Row fingerprint for change detection between an indexed row and its fresh fetch.
 * ponytail: a 32-bit hash can collide (~2^-32 per changed row) leaving one stale row;
 * exact field comparison would need all 175k names in heap — accepted ceiling.
 *
 * Poster is deliberately NOT part of the fingerprint: lazily enriched artwork (written by
 * PosterEnricher for panels whose bulk lists ship no icons) must not read as a "change" on
 * the next sync — the bulk row's empty icon would win and wipe the enrichment. Bulk icon
 * updates still land: the item write coalesces a non-null incoming poster over the stored one.
 */
internal fun itemFp(
    name: String, year: Int?, tmdb: Int?, ext: String?,
    categoryId: String? = null, epgId: String? = null, hasArchive: Boolean = false,
    pos: Int = 0,
): Int {
    var h = name.hashCode()
    h = 31 * h + (year ?: -1)
    h = 31 * h + (tmdb ?: -1)
    h = 31 * h + (ext?.hashCode() ?: 0)
    h = 31 * h + (categoryId?.hashCode() ?: 0)
    h = 31 * h + (epgId?.hashCode() ?: 0)
    h = 31 * h + if (hasArchive) 1 else 0
    h = 31 * h + pos
    return h
}

private fun IndexedItem.fp(): Int = itemFp(name, year, tmdb, ext, categoryId, epgId, hasArchive, pos)

/** Rows per streamed-sync DB flush — matches insertItems' chunk size. */
private const val FLUSH_CHUNK = 5_000

/**
 * Diffs a fresh catalog fetch against the indexed rows. [existingSids] MUST be ascending
 * (PK read order) and positionally aligned with [existingFps]. Unchanged rows cost one
 * binary search each — that's the whole "validate existing quickly" pass. Duplicate sids
 * in [fetched] (degenerate panels): first occurrence decides.
 */
internal fun diffCatalog(existingSids: IntArray, existingFps: IntArray, fetched: List<IndexedItem>): CatalogDiff {
    val seen = BooleanArray(existingSids.size)
    val upserts = ArrayList<IndexedItem>()
    val changedSids = ArrayList<Int>()
    for (item in fetched) {
        val i = existingSids.ascIndexOf(item.sid)
        if (i < 0) {
            upserts += item
        } else if (!seen[i]) {
            seen[i] = true
            if (existingFps[i] != item.fp()) {
                upserts += item
                changedSids += item.sid
            }
        }
    }
    val goneSids = ArrayList<Int>()
    for (i in existingSids.indices) if (!seen[i]) goneSids += existingSids[i]
    return CatalogDiff(upserts, changedSids, goneSids)
}

/** Binary search over an ascending IntArray (no boxing, common-Kotlin friendly). */
private fun IntArray.ascIndexOf(v: Int): Int {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val x = this[mid]
        when {
            x < v -> lo = mid + 1
            x > v -> hi = mid - 1
            else -> return mid
        }
    }
    return -1
}

data class UnsyncedMapping(val kind: String, val tmdb: Int, val sid: Int?, val matchedName: String?, val updatedAtMs: Long)

/**
 * Disk-backed lookup index per provider+kind: normalized-name keys and bulk-list tmdb ids
 * over the full catalog, plus the cache of verified tmdb->sid mappings (the thing Supabase
 * syncs across devices). Twin of NuvioMobile's XtreamMatchIndex, on framework SQLite.
 *
 * SQLite on purpose, not an in-memory map: a 175k-item catalog costs ~90MB as JVM maps —
 * fatal on 128-256MB TV heaps — vs ~2MB of page cache here, and it survives restarts.
 */
@Singleton
class XtreamMatchIndex @Inject constructor(@ApplicationContext context: Context) {

    private val helper = object : SQLiteOpenHelper(context, "xtream_match.db", null, 5) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE items(provider TEXT NOT NULL, kind TEXT NOT NULL, sid INTEGER NOT NULL, name TEXT NOT NULL, year INTEGER, tmdb INTEGER, ext TEXT, poster TEXT, category_id TEXT, epg_id TEXT, tv_archive INTEGER NOT NULL DEFAULT 0, pos INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider, kind, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX items_cat ON items(provider, kind, category_id, pos)")
            db.execSQL("CREATE TABLE cats(provider TEXT NOT NULL, kind TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, sort INTEGER NOT NULL, PRIMARY KEY(provider, kind, id)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX items_tmdb ON items(provider, kind, tmdb)")
            db.execSQL("CREATE TABLE keys(provider TEXT NOT NULL, kind TEXT NOT NULL, k TEXT NOT NULL, sid INTEGER NOT NULL, PRIMARY KEY(provider, kind, k, sid)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE idx_meta(provider TEXT NOT NULL, kind TEXT NOT NULL, built_at INTEGER NOT NULL, item_count INTEGER NOT NULL, last_added_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider, kind)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE tmdb_map(provider TEXT NOT NULL, kind TEXT NOT NULL, tmdb INTEGER NOT NULL, sid INTEGER, matched_name TEXT, updated_at INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider, kind, tmdb)) WITHOUT ROWID")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion >= 4) {
                // v5 is additive: idx_meta.last_added_at (negatives are only trusted when they
                // postdate the catalog's newest addition). Keep the v4 data — a full drop here
                // would cost every user a re-index for one column.
                db.execSQL("ALTER TABLE idx_meta ADD COLUMN last_added_at INTEGER NOT NULL DEFAULT 0")
                return
            }
            // pre-v4: index tables are rebuildable caches; mappings re-pull from Supabase
            db.execSQL("DROP TABLE IF EXISTS items"); db.execSQL("DROP TABLE IF EXISTS keys")
            db.execSQL("DROP TABLE IF EXISTS idx_meta"); db.execSQL("DROP TABLE IF EXISTS tmdb_map")
            db.execSQL("DROP TABLE IF EXISTS cats")
            onCreate(db)
        }
    }

    private val db: SQLiteDatabase by lazy { helper.writableDatabase }

    /**
     * Drops EVERYTHING stored for one provider (index + local mapping mirror) — account
     * removed. The Supabase copy of the mappings survives for other devices / a re-add.
     */
    suspend fun purge(provider: String) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            for (t in listOf("items", "keys", "idx_meta", "tmdb_map", "cats")) {
                db.delete(t, "provider = ?", arrayOf(provider))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun builtAt(provider: String, kind: MatchKind): Long? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT built_at FROM idx_meta WHERE provider = ? AND kind = ?", arrayOf(provider, kind.slug)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    /**
     * When this provider+kind last GAINED catalog items (0 = never observed). Tier-2 negative
     * mappings ("not on this provider") are only honored when they postdate this — a panel that
     * added titles invalidates every older miss, locally AND ones synced from other devices.
     */
    suspend fun lastAddedAt(provider: String, kind: MatchKind): Long = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT last_added_at FROM idx_meta WHERE provider = ? AND kind = ?",
            arrayOf(provider, kind.slug)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    /** Manual re-match reset: distrust every negative verdict recorded before now. */
    suspend fun distrustNegativeMappings(provider: String) = withContext(Dispatchers.IO) {
        db.execSQL(
            "UPDATE idx_meta SET last_added_at = ? WHERE provider = ?",
            arrayOf<Any?>(System.currentTimeMillis(), provider)
        )
    }

    /**
     * Indexed item count for a provider+kind, or null when never built — the playlist settings
     * row's catalog counts (item 8): the numbers already exist locally, zero API calls.
     */
    suspend fun indexedCount(provider: String, kind: MatchKind): Int? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT item_count FROM idx_meta WHERE provider = ? AND kind = ?", arrayOf(provider, kind.slug)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else null
        }
    }

    // --- browse catalog (P7, items 4-5): the hub reads Xtream sections from here --------------

    /** Replaces one provider+kind's category list (fetched alongside the catalog build). */
    suspend fun replaceCategories(provider: String, kind: MatchKind, categories: List<Pair<String, String>>) = withContext(Dispatchers.IO) {
        db.beginTransaction()
        try {
            db.delete("cats", "provider = ? AND kind = ?", arrayOf(provider, kind.slug))
            val st = db.compileStatement("INSERT OR REPLACE INTO cats(provider, kind, id, name, sort) VALUES(?,?,?,?,?)")
            categories.forEachIndexed { i, (id, name) ->
                st.clearBindings()
                st.bindString(1, provider); st.bindString(2, kind.slug)
                st.bindString(3, id); st.bindString(4, name); st.bindLong(5, i.toLong())
                st.executeInsert()
            }
            st.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The stored category list in panel order. Empty when the catalog was never built. */
    suspend fun categoriesFor(provider: String, kind: MatchKind): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id, name FROM cats WHERE provider = ? AND kind = ? ORDER BY sort", arrayOf(provider, kind.slug)).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
        }
    }

    /**
     * One window of a category (or the whole kind when [categoryId] is null), name-ordered.
     * THE item-5 read: the hub asks for [limit] rows from [offset] instead of materializing a
     * whole category — the covering items_cat index makes it a range scan.
     */
    suspend fun itemsFor(provider: String, kind: MatchKind, categoryId: String?, offset: Int, limit: Int): List<IndexedItem> = withContext(Dispatchers.IO) {
        val sql = if (categoryId == null)
            "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? ORDER BY pos, sid LIMIT ? OFFSET ?"
        else
            "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? AND category_id = ? ORDER BY pos, sid LIMIT ? OFFSET ?"
        val args = if (categoryId == null) arrayOf(provider, kind.slug, limit.toString(), offset.toString())
        else arrayOf(provider, kind.slug, categoryId, limit.toString(), offset.toString())
        db.rawQuery(sql, args).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    IndexedItem(
                        sid = c.getLong(0).toInt(),
                        name = c.getString(1),
                        year = if (c.isNull(2)) null else c.getLong(2).toInt(),
                        tmdb = if (c.isNull(3)) null else c.getLong(3).toInt(),
                        ext = if (c.isNull(4)) null else c.getString(4),
                        poster = if (c.isNull(5)) null else c.getString(5),
                        categoryId = if (c.isNull(6)) null else c.getString(6),
                        epgId = if (c.isNull(7)) null else c.getString(7),
                        hasArchive = c.getLong(8) > 0,
                    )
                )
            }
        }
    }

    /**
     * Replaces the whole index for one provider+kind. Chunked transactions keep the write
     * lock short; the meta row is written LAST so a crashed rebuild reads as stale.
     */
    suspend fun rebuild(provider: String, kind: MatchKind, itemsIn: List<IndexedItem>) = withContext(Dispatchers.IO) {
        val items = itemsIn.mapIndexed { i, raw -> if (raw.pos == i) raw else raw.copy(pos = i) }
        db.beginTransaction()
        try {
            db.delete("items", "provider = ? AND kind = ?", arrayOf(provider, kind.slug))
            db.delete("keys", "provider = ? AND kind = ?", arrayOf(provider, kind.slug))
            db.delete("idx_meta", "provider = ? AND kind = ?", arrayOf(provider, kind.slug))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        insertItems(provider, kind, items)
        writeMeta(provider, kind, items.size, addedCount = items.size)
    }

    /**
     * Incrementally reconciles the index with a fresh catalog fetch: unchanged rows are
     * validated by fingerprint only (no re-normalization, no rewrite), new/renamed rows are
     * (re)indexed, vanished rows deleted. Falls back to [rebuild] when the index is empty or
     * the catalog reshuffled wholesale. built_at is bumped LAST so a crashed sync reads as
     * stale and re-runs (idempotent).
     */
    suspend fun sync(provider: String, kind: MatchKind, itemsIn: List<IndexedItem>): SyncStats = withContext(Dispatchers.IO) {
        val items = itemsIn.mapIndexed { i, raw -> if (raw.pos == i) raw else raw.copy(pos = i) }
        // One streaming pass over the existing rows -> primitive (sid, fingerprint) arrays,
        // PK-ordered. ~1.4MB for a 175k catalog; never materializes the old names in heap.
        var sids = IntArray(4_096)
        var fps = IntArray(4_096)
        var count = 0
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive, pos FROM items WHERE provider = ? AND kind = ? ORDER BY sid",
            arrayOf(provider, kind.slug)
        ).use { c ->
            while (c.moveToNext()) {
                if (count == sids.size) {
                    sids = sids.copyOf(count * 2); fps = fps.copyOf(count * 2)
                }
                sids[count] = c.getLong(0).toInt()
                fps[count] = itemFp(
                    name = c.getString(1),
                    year = if (c.isNull(2)) null else c.getLong(2).toInt(),
                    tmdb = if (c.isNull(3)) null else c.getLong(3).toInt(),
                    ext = if (c.isNull(4)) null else c.getString(4),
                    categoryId = if (c.isNull(6)) null else c.getString(6),
                    epgId = if (c.isNull(7)) null else c.getString(7),
                    hasArchive = c.getLong(8) > 0,
                    pos = c.getLong(9).toInt(),
                )
                count++
            }
        }
        if (count == 0) {
            rebuild(provider, kind, items)
            return@withContext SyncStats(added = items.size, changed = 0, removed = 0, total = items.size)
        }
        // A glitchy panel returning an empty list must not wipe a good index — keep it,
        // don't bump built_at, let the next window retry.
        if (items.isEmpty()) return@withContext SyncStats(0, 0, 0, count)

        val diff = diffCatalog(sids.copyOf(count), fps.copyOf(count), items)
        // A wholesale reshuffle (provider migration, sid renumbering) is cheaper as a clean rebuild.
        if (diff.upserts.size + diff.goneSids.size > maxOf(500, count / 3)) {
            rebuild(provider, kind, items)
            return@withContext SyncStats(added = items.size, changed = 0, removed = 0, total = items.size)
        }

        // Deletes first: renamed rows' old name-keys and vanished rows. Then the (small) upsert
        // set rides the same chunked insert path as a full rebuild.
        db.beginTransaction()
        try {
            for (chunk in (diff.changedSids + diff.goneSids).chunked(500)) {
                val ph = chunk.joinToString(",") { "?" }
                val args = (listOf(provider, kind.slug) + chunk.map { it.toString() }).toTypedArray()
                db.delete("keys", "provider = ? AND kind = ? AND sid IN ($ph)", args)
            }
            for (chunk in diff.goneSids.chunked(500)) {
                val ph = chunk.joinToString(",") { "?" }
                val args = (listOf(provider, kind.slug) + chunk.map { it.toString() }).toTypedArray()
                db.delete("items", "provider = ? AND kind = ? AND sid IN ($ph)", args)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        insertItems(provider, kind, diff.upserts)
        writeMeta(provider, kind, items.size, addedCount = diff.upserts.size - diff.changedSids.size)
        SyncStats(
            added = diff.upserts.size - diff.changedSids.size,
            changed = diff.changedSids.size,
            removed = diff.goneSids.size,
            total = items.size,
        )
    }

    /**
     * Opens a streaming sync: rows are fed one at a time as the response parses
     * ([SyncSession.accept]) and flushed every [SyncSession.FLUSH_CHUNK], so peak heap is one
     * chunk (~5k items) instead of the whole catalog. Finalization (vanished-row deletes + the
     * built_at bump) happens ONLY in [SyncSession.finish]; the parser throws on a truncated body
     * before the caller gets there, and rows applied before an abort are harmless (idempotent
     * INSERT OR REPLACE, meta untouched, next sync re-runs).
     *
     * Semantics vs [sync]: identical minus the wholesale-reshuffle rebuild shortcut — streaming
     * can't know the diff size up front, so a renumbered catalog takes the (correct, chunked)
     * incremental path. On an empty index this IS a streamed rebuild.
     */
    suspend fun beginSync(provider: String, kind: MatchKind): SyncSession = withContext(Dispatchers.IO) {
        var sids = IntArray(4_096)
        var fps = IntArray(4_096)
        var count = 0
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive, pos FROM items WHERE provider = ? AND kind = ? ORDER BY sid",
            arrayOf(provider, kind.slug)
        ).use { c ->
            while (c.moveToNext()) {
                if (count == sids.size) {
                    sids = sids.copyOf(count * 2); fps = fps.copyOf(count * 2)
                }
                sids[count] = c.getLong(0).toInt()
                fps[count] = itemFp(
                    name = c.getString(1),
                    year = if (c.isNull(2)) null else c.getLong(2).toInt(),
                    tmdb = if (c.isNull(3)) null else c.getLong(3).toInt(),
                    ext = if (c.isNull(4)) null else c.getString(4),
                    categoryId = if (c.isNull(6)) null else c.getString(6),
                    epgId = if (c.isNull(7)) null else c.getString(7),
                    hasArchive = c.getLong(8) > 0,
                    pos = c.getLong(9).toInt(),
                )
                count++
            }
        }
        if (count == 0) {
            // First build (or a crashed one): clear leftovers so the stream is a clean rebuild.
            // idx_meta goes too, so builtAt reads null until finish() writes it (the caller's
            // "empty list on a first build is OK" check depends on that).
            db.beginTransaction()
            try {
                for (table in listOf("items", "keys", "idx_meta")) {
                    db.delete(table, "provider = ? AND kind = ?", arrayOf(provider, kind.slug))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        SyncSession(provider, kind, sids.copyOf(count), fps.copyOf(count))
    }

    /**
     * One in-flight streaming sync. Not thread-safe — feed it from the single response-reader
     * thread (the OkHttp body is already consumed on Dispatchers.IO, so the blocking flushes
     * land on the right thread by construction).
     */
    inner class SyncSession internal constructor(
        private val provider: String,
        private val kind: MatchKind,
        private val existingSids: IntArray,
        private val existingFps: IntArray,
    ) {
        private val seen = BooleanArray(existingSids.size)
        private val pending = ArrayList<IndexedItem>(FLUSH_CHUNK)
        private val pendingChanged = ArrayList<Int>()
        private var fetched = 0
        private var added = 0
        private var changed = 0

        /** Accepts one parsed row. Duplicate sids: first occurrence decides, like [diffCatalog]. */
        fun accept(raw: IndexedItem) {
            fetched++
            // Stamp arrival order — the panel's list order IS the browse order (never sorted).
            val item = raw.copy(pos = fetched - 1)
            val i = existingSids.ascIndexOf(item.sid)
            if (i < 0) {
                pending += item
                added++
            } else if (!seen[i]) {
                seen[i] = true
                if (existingFps[i] != item.fp()) {
                    pending += item
                    pendingChanged += item.sid
                    changed++
                }
            }
            if (pending.size >= FLUSH_CHUNK) flush()
        }

        private fun flush() {
            if (pending.isEmpty()) return
            // Renamed rows' old name-keys must go before their new keys land (same order sync()
            // guarantees via its up-front delete).
            if (pendingChanged.isNotEmpty()) {
                db.beginTransaction()
                try {
                    for (chunk in pendingChanged.chunked(500)) {
                        val ph = chunk.joinToString(",") { "?" }
                        val args = (listOf(provider, kind.slug) + chunk.map { it.toString() }).toTypedArray()
                        db.delete("keys", "provider = ? AND kind = ? AND sid IN ($ph)", args)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            insertItems(provider, kind, pending)
            pending.clear()
            pendingChanged.clear()
        }

        /**
         * Flushes the tail, deletes rows the fetch no longer contains, and bumps built_at LAST.
         * An empty fetch against an existing index is a panel glitch — nothing is deleted and
         * built_at stays stale so the next window retries (mirrors [sync]).
         */
        fun finish(): SyncStats {
            if (fetched == 0 && existingSids.isNotEmpty()) return SyncStats(0, 0, 0, existingSids.size)
            flush()
            val gone = ArrayList<Int>()
            for (i in existingSids.indices) if (!seen[i]) gone += existingSids[i]
            if (gone.isNotEmpty()) {
                db.beginTransaction()
                try {
                    for (chunk in gone.chunked(500)) {
                        val ph = chunk.joinToString(",") { "?" }
                        val args = (listOf(provider, kind.slug) + chunk.map { it.toString() }).toTypedArray()
                        db.delete("keys", "provider = ? AND kind = ? AND sid IN ($ph)", args)
                        db.delete("items", "provider = ? AND kind = ? AND sid IN ($ph)", args)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            writeMeta(provider, kind, fetched, addedCount = added)
            return SyncStats(added = added, changed = changed, removed = gone.size, total = fetched)
        }
    }

    private fun insertItems(provider: String, kind: MatchKind, items: List<IndexedItem>) {
        for (chunk in items.chunked(5_000)) {
            db.beginTransaction()
            try {
                // UPDATE-then-INSERT rather than INSERT OR REPLACE: an existing row's poster must
                // survive an incoming null (B-style panels ship empty bulk icons; the stored value
                // may be PosterEnricher's work). COALESCE keeps non-null incoming icons flowing.
                // Framework SQLite on the oldest supported TVs predates UPSERT, hence two steps.
                val updStmt = db.compileStatement("UPDATE items SET name=?, year=?, tmdb=?, ext=?, poster=COALESCE(?, poster), category_id=?, epg_id=?, tv_archive=?, pos=? WHERE provider=? AND kind=? AND sid=?")
                val insStmt = db.compileStatement("INSERT OR REPLACE INTO items(provider, kind, sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive, pos) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
                val keyStmt = db.compileStatement("INSERT OR REPLACE INTO keys(provider, kind, k, sid) VALUES(?,?,?,?)")
                for (it in chunk) {
                    updStmt.clearBindings()
                    updStmt.bindString(1, it.name)
                    if (it.year != null) updStmt.bindLong(2, it.year.toLong()) else updStmt.bindNull(2)
                    if (it.tmdb != null) updStmt.bindLong(3, it.tmdb.toLong()) else updStmt.bindNull(3)
                    if (it.ext != null) updStmt.bindString(4, it.ext) else updStmt.bindNull(4)
                    if (it.poster != null) updStmt.bindString(5, it.poster) else updStmt.bindNull(5)
                    if (it.categoryId != null) updStmt.bindString(6, it.categoryId) else updStmt.bindNull(6)
                    if (it.epgId != null) updStmt.bindString(7, it.epgId) else updStmt.bindNull(7)
                    updStmt.bindLong(8, if (it.hasArchive) 1L else 0L)
                    updStmt.bindLong(9, it.pos.toLong())
                    updStmt.bindString(10, provider); updStmt.bindString(11, kind.slug); updStmt.bindLong(12, it.sid.toLong())
                    if (updStmt.executeUpdateDelete() == 0) {
                        insStmt.clearBindings()
                        insStmt.bindString(1, provider); insStmt.bindString(2, kind.slug); insStmt.bindLong(3, it.sid.toLong())
                        insStmt.bindString(4, it.name)
                        if (it.year != null) insStmt.bindLong(5, it.year.toLong()) else insStmt.bindNull(5)
                        if (it.tmdb != null) insStmt.bindLong(6, it.tmdb.toLong()) else insStmt.bindNull(6)
                        if (it.ext != null) insStmt.bindString(7, it.ext) else insStmt.bindNull(7)
                        if (it.poster != null) insStmt.bindString(8, it.poster) else insStmt.bindNull(8)
                        if (it.categoryId != null) insStmt.bindString(9, it.categoryId) else insStmt.bindNull(9)
                        if (it.epgId != null) insStmt.bindString(10, it.epgId) else insStmt.bindNull(10)
                        insStmt.bindLong(11, if (it.hasArchive) 1L else 0L)
                        insStmt.bindLong(12, it.pos.toLong())
                        insStmt.executeInsert()
                    }
                    for (key in TitleNormalizer.keysOf(it.name)) {
                        keyStmt.clearBindings()
                        keyStmt.bindString(1, provider); keyStmt.bindString(2, kind.slug); keyStmt.bindString(3, key); keyStmt.bindLong(4, it.sid.toLong())
                        keyStmt.executeInsert()
                    }
                }
                updStmt.close(); insStmt.close(); keyStmt.close()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    /**
     * PosterEnricher's write-back: artwork learned from get_vod_info/get_series_info for a row
     * whose bulk list carried no icon. Survives syncs because [itemFp] ignores poster and the
     * sync write coalesces incoming nulls over it.
     */
    suspend fun updatePoster(provider: String, kind: MatchKind, sid: Int, poster: String) = withContext(Dispatchers.IO) {
        db.execSQL(
            "UPDATE items SET poster = ? WHERE provider = ? AND kind = ? AND sid = ?",
            arrayOf<Any>(poster, provider, kind.slug, sid.toString())
        )
    }

    private fun writeMeta(provider: String, kind: MatchKind, itemCount: Int, addedCount: Int) {
        db.beginTransaction()
        try {
            val previousLastAdded = db.rawQuery(
                "SELECT last_added_at FROM idx_meta WHERE provider = ? AND kind = ?",
                arrayOf(provider, kind.slug)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            // Catalog gained titles -> every older negative verdict is suspect (see lastAddedAt).
            val lastAdded = if (addedCount > 0) System.currentTimeMillis() else previousLastAdded
            db.execSQL(
                "INSERT OR REPLACE INTO idx_meta(provider, kind, built_at, item_count, last_added_at) VALUES(?,?,?,?,?)",
                arrayOf<Any?>(provider, kind.slug, System.currentTimeMillis(), itemCount, lastAdded)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Substring name search over the indexed catalog — backs the IPTV rows in Search. */
    suspend fun searchByName(provider: String, kind: MatchKind, query: String, limit: Int): List<IndexedItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? AND name LIKE '%' || ? || '%' LIMIT ?",
            arrayOf(provider, kind.slug, query, limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        IndexedItem(
                            sid = c.getLong(0).toInt(),
                            name = c.getString(1),
                            year = if (c.isNull(2)) null else c.getLong(2).toInt(),
                            tmdb = if (c.isNull(3)) null else c.getLong(3).toInt(),
                            ext = if (c.isNull(4)) null else c.getString(4),
                            poster = if (c.isNull(5)) null else c.getString(5),
                            categoryId = if (c.isNull(6)) null else c.getString(6),
                            epgId = if (c.isNull(7)) null else c.getString(7),
                            hasArchive = c.getLong(8) > 0,
                        )
                    )
                }
            }
        }
    }

    /** All items indexed under a normalized key. */
    suspend fun probe(provider: String, kind: MatchKind, key: String): List<IndexedItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT i.sid, i.name, i.year, i.tmdb, i.ext, i.poster, i.category_id, i.epg_id, i.tv_archive FROM keys x JOIN items i ON i.provider = x.provider AND i.kind = x.kind AND i.sid = x.sid WHERE x.provider = ? AND x.kind = ? AND x.k = ?",
            arrayOf(provider, kind.slug, key)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        IndexedItem(
                            sid = c.getLong(0).toInt(),
                            name = c.getString(1),
                            year = if (c.isNull(2)) null else c.getLong(2).toInt(),
                            tmdb = if (c.isNull(3)) null else c.getLong(3).toInt(),
                            ext = if (c.isNull(4)) null else c.getString(4),
                            poster = if (c.isNull(5)) null else c.getString(5),
                            categoryId = if (c.isNull(6)) null else c.getString(6),
                            epgId = if (c.isNull(7)) null else c.getString(7),
                            hasArchive = c.getLong(8) > 0,
                        )
                    )
                }
            }
        }
    }

    /** Tier-1: items whose bulk-list tmdb id already matches. */
    suspend fun byTmdb(provider: String, kind: MatchKind, tmdb: Int): List<IndexedItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? AND tmdb = ?",
            arrayOf(provider, kind.slug, tmdb.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        IndexedItem(
                            sid = c.getLong(0).toInt(),
                            name = c.getString(1),
                            year = if (c.isNull(2)) null else c.getLong(2).toInt(),
                            tmdb = if (c.isNull(3)) null else c.getLong(3).toInt(),
                            ext = if (c.isNull(4)) null else c.getString(4),
                            poster = if (c.isNull(5)) null else c.getString(5),
                            categoryId = if (c.isNull(6)) null else c.getString(6),
                            epgId = if (c.isNull(7)) null else c.getString(7),
                            hasArchive = c.getLong(8) > 0,
                        )
                    )
                }
            }
        }
    }

    suspend fun item(provider: String, kind: MatchKind, sid: Int): IndexedItem? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext, poster, category_id, epg_id, tv_archive FROM items WHERE provider = ? AND kind = ? AND sid = ?",
            arrayOf(provider, kind.slug, sid.toString())
        ).use { c ->
            if (!c.moveToFirst()) null
            else IndexedItem(
                sid = c.getLong(0).toInt(),
                name = c.getString(1),
                year = if (c.isNull(2)) null else c.getLong(2).toInt(),
                tmdb = if (c.isNull(3)) null else c.getLong(3).toInt(),
                ext = if (c.isNull(4)) null else c.getString(4),
                poster = if (c.isNull(5)) null else c.getString(5),
                categoryId = if (c.isNull(6)) null else c.getString(6),
                epgId = if (c.isNull(7)) null else c.getString(7),
                hasArchive = c.getLong(8) > 0,
            )
        }
    }

    // --- verified-mapping cache (local mirror of the Supabase iptv_tmdb_map rows) ---

    suspend fun cachedMapping(provider: String, kind: MatchKind, tmdb: Int): CachedMapping? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, matched_name, updated_at FROM tmdb_map WHERE provider = ? AND kind = ? AND tmdb = ?",
            arrayOf(provider, kind.slug, tmdb.toString())
        ).use { c ->
            if (!c.moveToFirst()) null
            else CachedMapping(
                sid = if (c.isNull(0)) null else c.getLong(0).toInt(),
                matchedName = if (c.isNull(1)) null else c.getString(1),
                updatedAtMs = c.getLong(2),
            )
        }
    }

    suspend fun putMapping(
        provider: String, kind: MatchKind, tmdb: Int, sid: Int?, matchedName: String?,
        synced: Boolean = false, updatedAtMs: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        db.execSQL(
            "INSERT OR REPLACE INTO tmdb_map(provider, kind, tmdb, sid, matched_name, updated_at, synced) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(provider, kind.slug, tmdb, sid, matchedName, updatedAtMs, if (synced) 1 else 0)
        )
    }

    /** Rows not yet pushed to Supabase. */
    suspend fun unsyncedMappings(provider: String): List<UnsyncedMapping> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT kind, tmdb, sid, matched_name, updated_at FROM tmdb_map WHERE provider = ? AND synced = 0",
            arrayOf(provider)
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        UnsyncedMapping(
                            kind = c.getString(0),
                            tmdb = c.getLong(1).toInt(),
                            sid = if (c.isNull(2)) null else c.getLong(2).toInt(),
                            matchedName = if (c.isNull(3)) null else c.getString(3),
                            updatedAtMs = c.getLong(4),
                        )
                    )
                }
            }
        }
    }

    suspend fun markSynced(provider: String, kind: String, tmdb: Int) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE tmdb_map SET synced = 1 WHERE provider = ? AND kind = ? AND tmdb = ?", arrayOf<Any?>(provider, kind, tmdb))
    }
}
