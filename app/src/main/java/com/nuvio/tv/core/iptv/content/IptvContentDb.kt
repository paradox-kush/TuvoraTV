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
/** One XMLTV programme spanning [startMs, endMs), keyed to a channel by its (normalized) EPG id. */
data class EpgProgramme(val channelId: String, val startMs: Long, val endMs: Long, val title: String, val desc: String?)

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

    private val helper = object : SQLiteOpenHelper(context, "iptv_content.db", null, 2) {
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
            // tvg_url = the url-tvg/x-tvg-url captured from the #EXTM3U header (default XMLTV source);
            // epg_built_at = when this playlist's EPG was last fetched (throttles the ~2×/day refresh).
            db.execSQL("CREATE TABLE ingest_meta(playlist_id TEXT NOT NULL PRIMARY KEY, built_at INTEGER NOT NULL, live_count INTEGER NOT NULL, vod_count INTEGER NOT NULL, series_count INTEGER NOT NULL, tvg_url TEXT, epg_built_at INTEGER) WITHOUT ROWID")
            createEpgTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Everything here is a rebuildable cache of the parsed playlist — drop + re-ingest.
            for (t in listOf("channels", "vod", "series", "episodes", "categories", "ingest_meta", "epg_programmes")) {
                db.execSQL("DROP TABLE IF EXISTS $t")
            }
            onCreate(db)
        }

        /** XMLTV now/next store: one row per programme, looked up by (playlist, channel, time). */
        private fun createEpgTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE epg_programmes(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, desc TEXT)")
            db.execSQL("CREATE INDEX epg_lookup ON epg_programmes(playlist_id, channel_id, start_ms)")
        }
    }

    private val db: SQLiteDatabase by lazy { helper.writableDatabase }

    /** Non-null when the playlist has a completed ingest. */
    suspend fun builtAt(playlistId: String): Long? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT built_at FROM ingest_meta WHERE playlist_id = ?", arrayOf(playlistId)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    /** The default XMLTV EPG url captured from the M3U's #EXTM3U header (null if none / not built). */
    suspend fun tvgUrl(playlistId: String): String? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT tvg_url FROM ingest_meta WHERE playlist_id = ?", arrayOf(playlistId)).use { c ->
            if (c.moveToFirst()) c.getStringOrNull(0) else null
        }
    }

    /** When this playlist's EPG was last fetched (null = never — refresh it). */
    suspend fun epgBuiltAt(playlistId: String): Long? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT epg_built_at FROM ingest_meta WHERE playlist_id = ?", arrayOf(playlistId)).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
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
        private var tvgUrl: String? = null   // url-tvg/x-tvg-url from the #EXTM3U header, if any

        /** Capture the M3U header's default XMLTV EPG url (persisted with the meta row). */
        fun setTvgUrl(url: String) { if (tvgUrl == null && url.isNotBlank()) tvgUrl = url }

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

        /** Flush remaining batches then write the crash-safe meta row LAST. epg_built_at is left
         *  null so a fresh ingest re-fetches the EPG (the catalog's channel set may have changed). */
        internal fun finish() {
            flushAll()
            inTx {
                db.execSQL(
                    "INSERT OR REPLACE INTO ingest_meta(playlist_id, built_at, live_count, vod_count, series_count, tvg_url, epg_built_at) VALUES(?,?,?,?,?,?,NULL)",
                    arrayOf<Any?>(playlistId, System.currentTimeMillis(), counts.live, counts.vod, counts.series, tvgUrl)
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
            // NOTE: epg_programmes is intentionally NOT cleared here. A catalog re-ingest resets the
            // meta's epg_built_at (finish writes NULL) so the EPG re-fetches, but the old programmes
            // stay readable until that fetch replaces them (via replaceEpg) — no now/next gap.
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

    // --- EPG (XMLTV for M3U live now/next) ----------------------------------

    /**
     * The distinct, NORMALIZED (trim+lowercase) EPG channel ids present in this playlist's live
     * channels. The XMLTV parse filters to this set so a 100MB+ guide never fully lands in the DB —
     * only programmes for channels the user actually has are stored.
     */
    suspend fun channelTvgIds(playlistId: String): Set<String> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT DISTINCT tvg_id FROM channels WHERE playlist_id = ? AND tvg_id IS NOT NULL", arrayOf(playlistId)).use { c ->
            buildSet { while (c.moveToNext()) c.getStringOrNull(0)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { add(it) } }
        }
    }

    /**
     * Replace this playlist's EPG in one pass: clear its old programmes, stream new ones in via the
     * [fill] block ([EpgWriter] batches [CHUNK]-sized inserts), then stamp epg_built_at LAST so a
     * crash mid-write reads as "not built" and the next refresh retries. channel_id is stored
     * already-normalized by the caller so lookups are a plain equality match.
     */
    suspend fun replaceEpg(playlistId: String, builtAtMs: Long, fill: suspend (EpgWriter) -> Unit) = withContext(Dispatchers.IO) {
        inTx { db.delete("epg_programmes", "playlist_id = ?", arrayOf(playlistId)) }
        val writer = EpgWriter(playlistId)
        fill(writer)
        writer.flush()
        // Stamp freshness last (row exists from the catalog ingest; UPDATE it).
        inTx { db.execSQL("UPDATE ingest_meta SET epg_built_at = ? WHERE playlist_id = ?", arrayOf<Any?>(builtAtMs, playlistId)) }
    }

    /** Batches programme inserts during an XMLTV parse (mirrors IngestWriter's chunking). */
    inner class EpgWriter internal constructor(private val playlistId: String) {
        private val batch = ArrayList<EpgProgramme>(CHUNK)
        var count = 0; private set

        fun add(p: EpgProgramme) {
            batch.add(p); count++
            if (batch.size >= CHUNK) flush()
        }

        internal fun flush() {
            if (batch.isEmpty()) return
            inTx {
                val s = db.compileStatement("INSERT INTO epg_programmes(playlist_id, channel_id, start_ms, end_ms, title, desc) VALUES(?,?,?,?,?,?)")
                for (p in batch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindString(2, p.channelId)
                    s.bindLong(3, p.startMs); s.bindLong(4, p.endMs); s.bindString(5, p.title)
                    bindNullable(s, 6, p.desc)
                    s.executeInsert()
                }
                s.close()
            }
            batch.clear()
        }
    }

    /**
     * Now + next programme for a channel: the programme whose window spans [nowMs] (or, if none is
     * live, the next upcoming one) plus the one immediately after it. [channelId] must already be
     * normalized (trim+lowercase) by the caller — the stored ids are. Returns up to 2 rows ordered
     * by start; empty when the channel has no EPG. Cheap (indexed range scan, LIMIT 2).
     */
    suspend fun epgNowNext(playlistId: String, channelId: String, nowMs: Long): List<EpgProgramme> = withContext(Dispatchers.IO) {
        // The current programme (latest one that started at/before now and hasn't ended) + the next.
        // A single query: everything ending after now, ordered by start, take 2. The first is "now"
        // if it already started, else the schedule has a gap and it's the upcoming programme.
        db.rawQuery(
            "SELECT channel_id, start_ms, end_ms, title, desc FROM epg_programmes WHERE playlist_id = ? AND channel_id = ? AND end_ms > ? ORDER BY start_ms LIMIT 2",
            arrayOf(playlistId, channelId, nowMs.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(EpgProgramme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getStringOrNull(4)))
            }
        }
    }

    companion object {
        const val TYPE_LIVE = "live"
        const val TYPE_VOD = "vod"
        const val TYPE_SERIES = "series"
        /** Insert batch size — matches XtreamMatchIndex's chunk to keep write locks short. */
        const val CHUNK = 5_000
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
