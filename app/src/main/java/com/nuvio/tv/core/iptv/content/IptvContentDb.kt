package com.nuvio.tv.core.iptv.content

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One live/vod row as stored/queried. sid is the synthetic per-playlist stream id. */
data class ContentChannel(val sid: Int, val name: String, val logo: String?, val tvgId: String?, val categoryId: String?, val url: String)
data class ContentVod(val sid: Int, val name: String, val logo: String?, val categoryId: String?, val url: String, val ext: String?)
/** A series HEADER (grouped M3U episodes). [sid] is a synthetic id derived from the series name. */
data class ContentSeries(val sid: Int, val name: String, val logo: String?, val categoryId: String?)
/** One episode under a series header, with its direct stream URL. */
data class ContentEpisode(val seriesSid: Int, val episodeSid: String, val season: Int, val episodeNum: Int, val title: String, val logo: String?, val url: String, val ext: String?)
data class ContentCategory(val id: String, val name: String)

/**
 * Disk-backed catalog for M3U/URL playlists. Unlike Xtream (which has a live API per browse),
 * a parsed M3U IS the catalog — a provider list can be 192MB / 685k entries, far too large to
 * hold in RAM or re-parse per browse. So [M3UClient] ingests the playlist once into this DB and
 * every hub/search/guide query reads from here.
 *
 * Twin of [com.nuvio.tv.core.iptv.match.XtreamMatchIndex]'s pattern: framework SQLiteOpenHelper,
 * WITHOUT ROWID tables keyed by playlist_id, chunked insert transactions to keep the write lock
 * short, and the ingest_meta row written LAST so a crashed/partial ingest reads as "not built".
 */
@Singleton
class IptvContentDb @Inject constructor(@ApplicationContext context: Context) {

    private val helper = object : SQLiteOpenHelper(context, "iptv_content.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE channels(playlist_id TEXT NOT NULL, category_id TEXT, sid INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, tvg_id TEXT, url TEXT NOT NULL, PRIMARY KEY(playlist_id, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX channels_cat ON channels(playlist_id, category_id)")
            db.execSQL("CREATE TABLE vod(playlist_id TEXT NOT NULL, category_id TEXT, sid INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, ext TEXT, PRIMARY KEY(playlist_id, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX vod_cat ON vod(playlist_id, category_id)")
            db.execSQL("CREATE TABLE series(playlist_id TEXT NOT NULL, category_id TEXT, sid INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, PRIMARY KEY(playlist_id, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX series_cat ON series(playlist_id, category_id)")
            db.execSQL("CREATE TABLE episodes(playlist_id TEXT NOT NULL, series_sid INTEGER NOT NULL, episode_sid TEXT NOT NULL, season INTEGER NOT NULL, episode_num INTEGER NOT NULL, title TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, ext TEXT, PRIMARY KEY(playlist_id, episode_sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX episodes_series ON episodes(playlist_id, series_sid)")
            db.execSQL("CREATE TABLE categories(playlist_id TEXT NOT NULL, type TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(playlist_id, type, id)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE ingest_meta(playlist_id TEXT NOT NULL PRIMARY KEY, built_at INTEGER NOT NULL, live_count INTEGER NOT NULL, vod_count INTEGER NOT NULL, series_count INTEGER NOT NULL) WITHOUT ROWID")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Everything here is a rebuildable cache of the parsed playlist — drop + re-ingest.
            for (t in listOf("channels", "vod", "series", "episodes", "categories", "ingest_meta")) {
                db.execSQL("DROP TABLE IF EXISTS $t")
            }
            onCreate(db)
        }
    }

    private val db: SQLiteDatabase by lazy { helper.writableDatabase }

    /** Non-null when the playlist has a completed ingest. */
    suspend fun builtAt(playlistId: String): Long? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT built_at FROM ingest_meta WHERE playlist_id = ?", arrayOf(playlistId)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    // --- Ingest -------------------------------------------------------------

    /** Accumulated during a streaming parse; flushed to the DB in chunks by [IngestWriter]. */
    private data class Counts(var live: Int = 0, var vod: Int = 0, var series: Int = 0)

    /**
     * A single ingest pass. Clears the playlist's old rows, then the caller feeds parsed rows in;
     * [IngestWriter] batches inserts into [CHUNK]-sized transactions. [finish] writes the meta row
     * LAST so a crash mid-ingest leaves [builtAt] null (reads as "not built" -> re-ingest).
     *
     * Series are grouped by header: [addEpisode] auto-creates the header row on first sight of a
     * (categoryId, seriesName) and returns the synthetic series sid.
     */
    inner class IngestWriter internal constructor(private val playlistId: String) {
        private val counts = Counts()
        private val channelBatch = ArrayList<ContentChannel>(CHUNK)
        private val vodBatch = ArrayList<ContentVod>(CHUNK)
        private val seriesBatch = ArrayList<ContentSeries>(CHUNK)
        private val episodeBatch = ArrayList<ContentEpisode>(CHUNK)
        private val categoryBatch = ArrayList<Triple<String, String, String>>()  // type, id, name
        private val seenCategories = HashSet<String>()   // "type|id"
        private val seriesSidByKey = HashMap<String, Int>()  // "categoryId|name" -> sid
        private var nextSeriesSid = 1
        private var nextEpisodeSeq = 0   // monotonic across chunks (batch index resets on flush)

        fun addChannel(row: ContentChannel) {
            channelBatch.add(row); counts.live++
            categoryOf(TYPE_LIVE, row.categoryId)
            if (channelBatch.size >= CHUNK) flushChannels()
        }

        fun addVod(row: ContentVod) {
            vodBatch.add(row); counts.vod++
            categoryOf(TYPE_VOD, row.categoryId)
            if (vodBatch.size >= CHUNK) flushVod()
        }

        /** Group an episode under its series header (created on first sight). */
        fun addEpisode(categoryId: String?, seriesName: String, season: Int, episodeNum: Int, title: String, logo: String?, url: String, ext: String?) {
            val key = "${categoryId.orEmpty()}|$seriesName"
            val seriesSid = seriesSidByKey.getOrPut(key) {
                val sid = nextSeriesSid++
                seriesBatch.add(ContentSeries(sid, seriesName, logo, categoryId)); counts.series++
                categoryOf(TYPE_SERIES, categoryId)
                if (seriesBatch.size >= CHUNK) flushSeries()
                sid
            }
            // episode_sid must be unique per playlist. A monotonic sequence (not the per-chunk
            // batch index, which resets on flush) guarantees uniqueness even for duplicate
            // season/episode numbers across chunks.
            episodeBatch.add(ContentEpisode(seriesSid, "e${nextEpisodeSeq++}", season, episodeNum, title, logo, url, ext))
            if (episodeBatch.size >= CHUNK) flushEpisodes()
        }

        private fun categoryOf(type: String, id: String?) {
            val catId = id ?: return
            if (seenCategories.add("$type|$catId")) categoryBatch.add(Triple(type, catId, catId))
        }

        internal fun flushAll() {
            if (channelBatch.isNotEmpty()) flushChannels()
            if (vodBatch.isNotEmpty()) flushVod()
            if (seriesBatch.isNotEmpty()) flushSeries()
            if (episodeBatch.isNotEmpty()) flushEpisodes()
            if (categoryBatch.isNotEmpty()) flushCategories()
        }

        // --- batched writers (each its own transaction) ---
        private fun flushChannels() {
            inTx {
                val s = db.compileStatement("INSERT OR REPLACE INTO channels(playlist_id, category_id, sid, name, logo, tvg_id, url) VALUES(?,?,?,?,?,?,?)")
                for (r in channelBatch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); bindNullable(s, 2, r.categoryId); s.bindLong(3, r.sid.toLong())
                    s.bindString(4, r.name); bindNullable(s, 5, r.logo); bindNullable(s, 6, r.tvgId); s.bindString(7, r.url)
                    s.executeInsert()
                }
                s.close()
            }
            channelBatch.clear()
        }

        private fun flushVod() {
            inTx {
                val s = db.compileStatement("INSERT OR REPLACE INTO vod(playlist_id, category_id, sid, name, logo, url, ext) VALUES(?,?,?,?,?,?,?)")
                for (r in vodBatch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); bindNullable(s, 2, r.categoryId); s.bindLong(3, r.sid.toLong())
                    s.bindString(4, r.name); bindNullable(s, 5, r.logo); s.bindString(6, r.url); bindNullable(s, 7, r.ext)
                    s.executeInsert()
                }
                s.close()
            }
            vodBatch.clear()
        }

        private fun flushSeries() {
            inTx {
                val s = db.compileStatement("INSERT OR REPLACE INTO series(playlist_id, category_id, sid, name, logo) VALUES(?,?,?,?,?)")
                for (r in seriesBatch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); bindNullable(s, 2, r.categoryId); s.bindLong(3, r.sid.toLong())
                    s.bindString(4, r.name); bindNullable(s, 5, r.logo)
                    s.executeInsert()
                }
                s.close()
            }
            seriesBatch.clear()
        }

        private fun flushEpisodes() {
            inTx {
                val s = db.compileStatement("INSERT OR REPLACE INTO episodes(playlist_id, series_sid, episode_sid, season, episode_num, title, logo, url, ext) VALUES(?,?,?,?,?,?,?,?,?)")
                for (r in episodeBatch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, r.seriesSid.toLong()); s.bindString(3, r.episodeSid)
                    s.bindLong(4, r.season.toLong()); s.bindLong(5, r.episodeNum.toLong()); s.bindString(6, r.title)
                    bindNullable(s, 7, r.logo); s.bindString(8, r.url); bindNullable(s, 9, r.ext)
                    s.executeInsert()
                }
                s.close()
            }
            episodeBatch.clear()
        }

        private fun flushCategories() {
            inTx {
                val s = db.compileStatement("INSERT OR REPLACE INTO categories(playlist_id, type, id, name) VALUES(?,?,?,?)")
                for ((type, id, name) in categoryBatch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindString(2, type); s.bindString(3, id); s.bindString(4, name)
                    s.executeInsert()
                }
                s.close()
            }
            categoryBatch.clear()
        }

        /** Flush remaining batches then write the crash-safe meta row LAST. */
        internal fun finish() {
            flushAll()
            inTx {
                db.execSQL(
                    "INSERT OR REPLACE INTO ingest_meta(playlist_id, built_at, live_count, vod_count, series_count) VALUES(?,?,?,?,?)",
                    arrayOf<Any?>(playlistId, System.currentTimeMillis(), counts.live, counts.vod, counts.series)
                )
            }
        }

        val liveCount get() = counts.live
        val vodCount get() = counts.vod
        val seriesCount get() = counts.series
    }

    private inline fun inTx(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun bindNullable(stmt: android.database.sqlite.SQLiteStatement, index: Int, value: String?) {
        if (value != null) stmt.bindString(index, value) else stmt.bindNull(index)
    }

    /**
     * Runs a full ingest atomically-ish: clears the playlist's old rows in one transaction, hands
     * an [IngestWriter] to [fill] (which streams rows in), then finalizes with the meta row last.
     * The old catalog stays readable until the clear commits; a crash before [finish] leaves
     * [builtAt] null so the next access re-ingests.
     */
    suspend fun ingest(playlistId: String, fill: suspend (IngestWriter) -> Unit): IngestWriter = withContext(Dispatchers.IO) {
        clear(playlistId)
        val writer = IngestWriter(playlistId)
        fill(writer)
        writer.finish()
        writer
    }

    suspend fun clear(playlistId: String) = withContext(Dispatchers.IO) {
        inTx {
            for (t in listOf("channels", "vod", "series", "episodes", "categories", "ingest_meta")) {
                db.delete(t, "playlist_id = ?", arrayOf(playlistId))
            }
        }
    }

    // --- Queries ------------------------------------------------------------

    suspend fun categoriesFor(playlistId: String, type: String): List<ContentCategory> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id, name FROM categories WHERE playlist_id = ? AND type = ? ORDER BY name", arrayOf(playlistId, type)).use { c ->
            buildList { while (c.moveToNext()) add(ContentCategory(c.getString(0), c.getString(1))) }
        }
    }

    /** [categoryId] null = every channel in the playlist. */
    suspend fun channelsFor(playlistId: String, categoryId: String?): List<ContentChannel> = withContext(Dispatchers.IO) {
        val (where, args) = catFilter(playlistId, categoryId)
        db.rawQuery("SELECT sid, name, logo, tvg_id, category_id, url FROM channels WHERE $where", args).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentChannel(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getString(5)))
            }
        }
    }

    suspend fun vodFor(playlistId: String, categoryId: String?): List<ContentVod> = withContext(Dispatchers.IO) {
        val (where, args) = catFilter(playlistId, categoryId)
        db.rawQuery("SELECT sid, name, logo, category_id, url, ext FROM vod WHERE $where", args).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentVod(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getString(4), c.getStringOrNull(5)))
            }
        }
    }

    suspend fun seriesFor(playlistId: String, categoryId: String?): List<ContentSeries> = withContext(Dispatchers.IO) {
        val (where, args) = catFilter(playlistId, categoryId)
        db.rawQuery("SELECT sid, name, logo, category_id FROM series WHERE $where", args).use { c ->
            buildList { while (c.moveToNext()) add(ContentSeries(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3))) }
        }
    }

    suspend fun episodesFor(playlistId: String, seriesSid: Int): List<ContentEpisode> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT series_sid, episode_sid, season, episode_num, title, logo, url, ext FROM episodes WHERE playlist_id = ? AND series_sid = ? ORDER BY season, episode_num",
            arrayOf(playlistId, seriesSid.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentEpisode(c.getInt(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4), c.getStringOrNull(5), c.getString(6), c.getStringOrNull(7)))
            }
        }
    }

    /** Direct URL of a single channel (live) — used to rebuild a deep-linked/saved item. */
    suspend fun channelUrl(playlistId: String, sid: Int): String? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT url FROM channels WHERE playlist_id = ? AND sid = ?", arrayOf(playlistId, sid.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    suspend fun vodUrl(playlistId: String, sid: Int): String? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT url FROM vod WHERE playlist_id = ? AND sid = ?", arrayOf(playlistId, sid.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    suspend fun channelRow(playlistId: String, sid: Int): ContentChannel? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT sid, name, logo, tvg_id, category_id, url FROM channels WHERE playlist_id = ? AND sid = ?", arrayOf(playlistId, sid.toString())).use { c ->
            if (c.moveToFirst()) ContentChannel(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getString(5)) else null
        }
    }

    suspend fun seriesRow(playlistId: String, sid: Int): ContentSeries? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? AND sid = ?", arrayOf(playlistId, sid.toString())).use { c ->
            if (c.moveToFirst()) ContentSeries(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3)) else null
        }
    }

    /** Substring name search within a content type (backs the IPTV rows in Search). */
    suspend fun searchChannels(playlistId: String, query: String, limit: Int): List<ContentChannel> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, logo, tvg_id, category_id, url FROM channels WHERE playlist_id = ? AND name LIKE '%' || ? || '%' LIMIT ?",
            arrayOf(playlistId, query, limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(ContentChannel(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getString(5))) }
        }
    }

    suspend fun searchVod(playlistId: String, query: String, limit: Int): List<ContentVod> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, logo, category_id, url, ext FROM vod WHERE playlist_id = ? AND name LIKE '%' || ? || '%' LIMIT ?",
            arrayOf(playlistId, query, limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(ContentVod(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getString(4), c.getStringOrNull(5))) }
        }
    }

    suspend fun searchSeries(playlistId: String, query: String, limit: Int): List<ContentSeries> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? AND name LIKE '%' || ? || '%' LIMIT ?",
            arrayOf(playlistId, query, limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(ContentSeries(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3))) }
        }
    }

    private fun catFilter(playlistId: String, categoryId: String?): Pair<String, Array<String>> =
        if (categoryId == null) "playlist_id = ?" to arrayOf(playlistId)
        else "playlist_id = ? AND category_id = ?" to arrayOf(playlistId, categoryId)

    companion object {
        const val TYPE_LIVE = "live"
        const val TYPE_VOD = "vod"
        const val TYPE_SERIES = "series"
        /** Insert batch size — matches XtreamMatchIndex's chunk to keep write locks short. */
        const val CHUNK = 5_000
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
