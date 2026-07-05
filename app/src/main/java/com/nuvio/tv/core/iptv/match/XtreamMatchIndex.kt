package com.nuvio.tv.core.iptv.match

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class MatchKind(val slug: String) { MOVIE("movie"), SERIES("series") }

/** One catalog entry as stored in the index. [ext] = container extension (movies only). */
data class IndexedItem(val sid: Int, val name: String, val year: Int?, val tmdb: Int?, val ext: String?, val poster: String? = null)

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
 */
internal fun itemFp(name: String, year: Int?, tmdb: Int?, ext: String?, poster: String?): Int {
    var h = name.hashCode()
    h = 31 * h + (year ?: -1)
    h = 31 * h + (tmdb ?: -1)
    h = 31 * h + (ext?.hashCode() ?: 0)
    h = 31 * h + (poster?.hashCode() ?: 0)
    return h
}

private fun IndexedItem.fp(): Int = itemFp(name, year, tmdb, ext, poster)

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

    private val helper = object : SQLiteOpenHelper(context, "xtream_match.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE items(provider TEXT NOT NULL, kind TEXT NOT NULL, sid INTEGER NOT NULL, name TEXT NOT NULL, year INTEGER, tmdb INTEGER, ext TEXT, poster TEXT, PRIMARY KEY(provider, kind, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX items_tmdb ON items(provider, kind, tmdb)")
            db.execSQL("CREATE TABLE keys(provider TEXT NOT NULL, kind TEXT NOT NULL, k TEXT NOT NULL, sid INTEGER NOT NULL, PRIMARY KEY(provider, kind, k, sid)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE idx_meta(provider TEXT NOT NULL, kind TEXT NOT NULL, built_at INTEGER NOT NULL, item_count INTEGER NOT NULL, PRIMARY KEY(provider, kind)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE tmdb_map(provider TEXT NOT NULL, kind TEXT NOT NULL, tmdb INTEGER NOT NULL, sid INTEGER, matched_name TEXT, updated_at INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(provider, kind, tmdb)) WITHOUT ROWID")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // index tables are rebuildable caches; mappings re-pull from Supabase
            db.execSQL("DROP TABLE IF EXISTS items"); db.execSQL("DROP TABLE IF EXISTS keys")
            db.execSQL("DROP TABLE IF EXISTS idx_meta"); db.execSQL("DROP TABLE IF EXISTS tmdb_map")
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
            for (t in listOf("items", "keys", "idx_meta", "tmdb_map")) {
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
     * Replaces the whole index for one provider+kind. Chunked transactions keep the write
     * lock short; the meta row is written LAST so a crashed rebuild reads as stale.
     */
    suspend fun rebuild(provider: String, kind: MatchKind, items: List<IndexedItem>) = withContext(Dispatchers.IO) {
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
        writeMeta(provider, kind, items.size)
    }

    /**
     * Incrementally reconciles the index with a fresh catalog fetch: unchanged rows are
     * validated by fingerprint only (no re-normalization, no rewrite), new/renamed rows are
     * (re)indexed, vanished rows deleted. Falls back to [rebuild] when the index is empty or
     * the catalog reshuffled wholesale. built_at is bumped LAST so a crashed sync reads as
     * stale and re-runs (idempotent).
     */
    suspend fun sync(provider: String, kind: MatchKind, items: List<IndexedItem>): SyncStats = withContext(Dispatchers.IO) {
        // One streaming pass over the existing rows -> primitive (sid, fingerprint) arrays,
        // PK-ordered. ~1.4MB for a 175k catalog; never materializes the old names in heap.
        var sids = IntArray(4_096)
        var fps = IntArray(4_096)
        var count = 0
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext, poster FROM items WHERE provider = ? AND kind = ? ORDER BY sid",
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
                    poster = if (c.isNull(5)) null else c.getString(5),
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
        writeMeta(provider, kind, items.size)
        SyncStats(
            added = diff.upserts.size - diff.changedSids.size,
            changed = diff.changedSids.size,
            removed = diff.goneSids.size,
            total = items.size,
        )
    }

    private fun insertItems(provider: String, kind: MatchKind, items: List<IndexedItem>) {
        for (chunk in items.chunked(5_000)) {
            db.beginTransaction()
            try {
                val itemStmt = db.compileStatement("INSERT OR REPLACE INTO items(provider, kind, sid, name, year, tmdb, ext, poster) VALUES(?,?,?,?,?,?,?,?)")
                val keyStmt = db.compileStatement("INSERT OR REPLACE INTO keys(provider, kind, k, sid) VALUES(?,?,?,?)")
                for (it in chunk) {
                    itemStmt.clearBindings()
                    itemStmt.bindString(1, provider); itemStmt.bindString(2, kind.slug); itemStmt.bindLong(3, it.sid.toLong())
                    itemStmt.bindString(4, it.name)
                    if (it.year != null) itemStmt.bindLong(5, it.year.toLong()) else itemStmt.bindNull(5)
                    if (it.tmdb != null) itemStmt.bindLong(6, it.tmdb.toLong()) else itemStmt.bindNull(6)
                    if (it.ext != null) itemStmt.bindString(7, it.ext) else itemStmt.bindNull(7)
                    if (it.poster != null) itemStmt.bindString(8, it.poster) else itemStmt.bindNull(8)
                    itemStmt.executeInsert()
                    for (key in TitleNormalizer.keysOf(it.name)) {
                        keyStmt.clearBindings()
                        keyStmt.bindString(1, provider); keyStmt.bindString(2, kind.slug); keyStmt.bindString(3, key); keyStmt.bindLong(4, it.sid.toLong())
                        keyStmt.executeInsert()
                    }
                }
                itemStmt.close(); keyStmt.close()
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    private fun writeMeta(provider: String, kind: MatchKind, itemCount: Int) {
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO idx_meta(provider, kind, built_at, item_count) VALUES(?,?,?,?)",
                arrayOf<Any?>(provider, kind.slug, System.currentTimeMillis(), itemCount)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Substring name search over the indexed catalog — backs the IPTV rows in Search. */
    suspend fun searchByName(provider: String, kind: MatchKind, query: String, limit: Int): List<IndexedItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext, poster FROM items WHERE provider = ? AND kind = ? AND name LIKE '%' || ? || '%' LIMIT ?",
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
                        )
                    )
                }
            }
        }
    }

    /** All items indexed under a normalized key. */
    suspend fun probe(provider: String, kind: MatchKind, key: String): List<IndexedItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT i.sid, i.name, i.year, i.tmdb, i.ext, i.poster FROM keys x JOIN items i ON i.provider = x.provider AND i.kind = x.kind AND i.sid = x.sid WHERE x.provider = ? AND x.kind = ? AND x.k = ?",
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
                        )
                    )
                }
            }
        }
    }

    /** Tier-1: items whose bulk-list tmdb id already matches. */
    suspend fun byTmdb(provider: String, kind: MatchKind, tmdb: Int): List<IndexedItem> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext, poster FROM items WHERE provider = ? AND kind = ? AND tmdb = ?",
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
                        )
                    )
                }
            }
        }
    }

    suspend fun item(provider: String, kind: MatchKind, sid: Int): IndexedItem? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, year, tmdb, ext, poster FROM items WHERE provider = ? AND kind = ? AND sid = ?",
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
