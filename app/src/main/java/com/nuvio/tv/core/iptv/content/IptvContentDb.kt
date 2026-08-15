package com.nuvio.tv.core.iptv.content

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One live/vod row as stored/queried. sid is the synthetic per-playlist stream id.
 *  [useHttpTmpLink]/[useLoadBalancing] mirror the Xtream panel's per-channel flags (stream
 *  resolution consumes them — this store only persists and returns them). */
data class ContentChannel(val sid: Int, val name: String, val logo: String?, val tvgId: String?, val categoryId: String?, val url: String, val cmd: String? = null, val hasArchive: Boolean = false, val useHttpTmpLink: Boolean = false, val useLoadBalancing: Boolean = false)
data class ContentVod(val sid: Int, val name: String, val logo: String?, val categoryId: String?, val url: String, val ext: String?, val cmd: String? = null)
/** A series HEADER (grouped M3U episodes). [sid] is a synthetic id derived from the series name. */
data class ContentSeries(val sid: Int, val name: String, val logo: String?, val categoryId: String?)
/** One episode under a series header, with its direct stream URL. */
data class ContentEpisode(val seriesSid: Int, val episodeSid: String, val season: Int, val episodeNum: Int, val title: String, val logo: String?, val url: String, val ext: String?, val cmd: String? = null)
data class ContentCategory(val id: String, val name: String)
/** One XMLTV programme spanning [startMs, endMs), keyed to a channel by its (normalized) EPG id.
 *  [hasArchive] = the programme is inside the provider's replay window (catch-up). Windowed
 *  reads truncate [desc] to 600 chars — [IptvContentDb.epgFullDesc] fetches the whole text. */
data class EpgProgramme(val channelId: String, val startMs: Long, val endMs: Long, val title: String, val desc: String?, val hasArchive: Boolean = false)

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

    // v4 (memory/catch-up pre-work, one migration): epg_programmes.has_archive, the
    // per-(playlist, channel) fetch-stamp table, and the Xtream channel flags. This helper
    // rebuilds on upgrade (everything here is a re-ingestable cache), so the bump IS the
    // migration and onCreate always carries the current schema.
    private val helper = object : SQLiteOpenHelper(context, "iptv_content.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE channels(playlist_id TEXT NOT NULL, category_id TEXT, sid INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, tvg_id TEXT, url TEXT NOT NULL, cmd TEXT, tv_archive INTEGER, use_http_tmp_link INTEGER, use_load_balancing INTEGER, PRIMARY KEY(playlist_id, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX channels_cat ON channels(playlist_id, category_id)")
            db.execSQL("CREATE TABLE vod(playlist_id TEXT NOT NULL, category_id TEXT, sid INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, ext TEXT, cmd TEXT, PRIMARY KEY(playlist_id, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX vod_cat ON vod(playlist_id, category_id)")
            db.execSQL("CREATE TABLE series(playlist_id TEXT NOT NULL, category_id TEXT, sid INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, PRIMARY KEY(playlist_id, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX series_cat ON series(playlist_id, category_id)")
            db.execSQL("CREATE TABLE episodes(playlist_id TEXT NOT NULL, series_sid INTEGER NOT NULL, episode_sid TEXT NOT NULL, season INTEGER NOT NULL, episode_num INTEGER NOT NULL, title TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, ext TEXT, cmd TEXT, PRIMARY KEY(playlist_id, episode_sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX episodes_series ON episodes(playlist_id, series_sid)")
            db.execSQL("CREATE TABLE categories(playlist_id TEXT NOT NULL, type TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(playlist_id, type, id)) WITHOUT ROWID")
            // tvg_url = the url-tvg/x-tvg-url captured from the #EXTM3U header (default XMLTV source);
            // epg_built_at = when this playlist's EPG was last fetched (throttles the ~2×/day refresh).
            db.execSQL("CREATE TABLE ingest_meta(playlist_id TEXT NOT NULL PRIMARY KEY, built_at INTEGER NOT NULL, live_count INTEGER NOT NULL, vod_count INTEGER NOT NULL, series_count INTEGER NOT NULL, tvg_url TEXT, epg_built_at INTEGER) WITHOUT ROWID")
            createEpgTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Everything here is a rebuildable cache of the parsed playlist — drop + re-ingest.
            for (t in listOf("channels", "vod", "series", "episodes", "categories", "ingest_meta", "epg_programmes", "epg_channel_fetch")) {
                db.execSQL("DROP TABLE IF EXISTS $t")
            }
            onCreate(db)
        }

        /** XMLTV now/next store: one row per programme, looked up by (playlist, channel, time). */
        private fun createEpgTable(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE epg_programmes(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, desc TEXT, has_archive INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE INDEX epg_lookup ON epg_programmes(playlist_id, channel_id, start_ms)")
            // Per-(playlist, channel) EPG fetch stamp — the guide's lazy-fetch gate.
            db.execSQL("CREATE TABLE epg_channel_fetch(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, fetched_at INTEGER NOT NULL, PRIMARY KEY(playlist_id, channel_id)) WITHOUT ROWID")
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

    /** live_count from the meta row — the lineup-usable check without loading 11k rows. */
    suspend fun liveCount(playlistId: String): Int = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT live_count FROM ingest_meta WHERE playlist_id = ?", arrayOf(playlistId)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
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
                val s = db.compileStatement("INSERT OR REPLACE INTO channels(playlist_id, category_id, sid, name, logo, tvg_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                for (r in channelBatch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); bindNullable(s, 2, r.categoryId); s.bindLong(3, r.sid.toLong())
                    s.bindString(4, r.name); bindNullable(s, 5, r.logo); bindNullable(s, 6, r.tvgId); s.bindString(7, r.url)
                    bindNullable(s, 8, r.cmd); s.bindLong(9, if (r.hasArchive) 1L else 0L)
                    s.bindLong(10, if (r.useHttpTmpLink) 1L else 0L); s.bindLong(11, if (r.useLoadBalancing) 1L else 0L)
                    s.executeInsert()
                }
                s.close()
            }
            channelBatch.clear()
        }

        private fun flushVod() {
            inTx {
                val s = db.compileStatement("INSERT OR REPLACE INTO vod(playlist_id, category_id, sid, name, logo, url, ext, cmd) VALUES(?,?,?,?,?,?,?,?)")
                for (r in vodBatch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); bindNullable(s, 2, r.categoryId); s.bindLong(3, r.sid.toLong())
                    s.bindString(4, r.name); bindNullable(s, 5, r.logo); s.bindString(6, r.url); bindNullable(s, 7, r.ext)
                    bindNullable(s, 8, r.cmd)
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
                val s = db.compileStatement("INSERT OR REPLACE INTO episodes(playlist_id, series_sid, episode_sid, season, episode_num, title, logo, url, ext, cmd) VALUES(?,?,?,?,?,?,?,?,?,?)")
                for (r in episodeBatch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, r.seriesSid.toLong()); s.bindString(3, r.episodeSid)
                    s.bindLong(4, r.season.toLong()); s.bindLong(5, r.episodeNum.toLong()); s.bindString(6, r.title)
                    bindNullable(s, 7, r.logo); s.bindString(8, r.url); bindNullable(s, 9, r.ext); bindNullable(s, 10, r.cmd)
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

    /** Full removal (playlist deleted): [clear] plus its EPG rows — nothing left on disk. */
    suspend fun purge(playlistId: String) {
        clear(playlistId)
        withContext(Dispatchers.IO) {
            inTx {
                db.delete("epg_programmes", "playlist_id = ?", arrayOf(playlistId))
                db.delete("epg_channel_fetch", "playlist_id = ?", arrayOf(playlistId))
            }
        }
    }

    // --- Queries ------------------------------------------------------------

    suspend fun categoriesFor(playlistId: String, type: String): List<ContentCategory> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id, name FROM categories WHERE playlist_id = ? AND type = ? ORDER BY name", arrayOf(playlistId, type)).use { c ->
            buildList { while (c.moveToNext()) add(ContentCategory(c.getString(0), c.getString(1))) }
        }
    }

    /**
     * Windowed reads (item 5): [limit] rows from [offset], name-ordered — the hub loads a first
     * window and appends as focus nears the row's end, instead of materializing a 10k-row category
     * as one List (which is how "M3U has a DB" still bloated the heap: storage without paging).
     */
    suspend fun pageChannels(playlistId: String, categoryId: String?, offset: Int, limit: Int): List<ContentChannel> = withContext(Dispatchers.IO) {
        val (where, args) = catFilter(playlistId, categoryId)
        db.rawQuery(
            "SELECT sid, name, logo, tvg_id, category_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing FROM channels WHERE $where ORDER BY name, sid LIMIT ? OFFSET ?",
            args + arrayOf(limit.toString(), offset.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentChannel(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getString(5), c.getStringOrNull(6), c.getInt(7) > 0, c.getInt(8) > 0, c.getInt(9) > 0))
            }
        }
    }

    suspend fun pageVod(playlistId: String, categoryId: String?, offset: Int, limit: Int): List<ContentVod> = withContext(Dispatchers.IO) {
        val (where, args) = catFilter(playlistId, categoryId)
        db.rawQuery(
            "SELECT sid, name, logo, category_id, url, ext, cmd FROM vod WHERE $where ORDER BY name, sid LIMIT ? OFFSET ?",
            args + arrayOf(limit.toString(), offset.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentVod(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getString(4), c.getStringOrNull(5), c.getStringOrNull(6)))
            }
        }
    }

    suspend fun pageSeries(playlistId: String, categoryId: String?, offset: Int, limit: Int): List<ContentSeries> = withContext(Dispatchers.IO) {
        val (where, args) = catFilter(playlistId, categoryId)
        db.rawQuery(
            "SELECT sid, name, logo, category_id FROM series WHERE $where ORDER BY name, sid LIMIT ? OFFSET ?",
            args + arrayOf(limit.toString(), offset.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(ContentSeries(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3))) }
        }
    }

    /** [categoryId] null = every channel in the playlist. */
    suspend fun channelsFor(playlistId: String, categoryId: String?): List<ContentChannel> = withContext(Dispatchers.IO) {
        val (where, args) = catFilter(playlistId, categoryId)
        db.rawQuery("SELECT sid, name, logo, tvg_id, category_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing FROM channels WHERE $where", args).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentChannel(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getString(5), c.getStringOrNull(6), c.getInt(7) > 0, c.getInt(8) > 0, c.getInt(9) > 0))
            }
        }
    }

    suspend fun vodFor(playlistId: String, categoryId: String?): List<ContentVod> = withContext(Dispatchers.IO) {
        val (where, args) = catFilter(playlistId, categoryId)
        db.rawQuery("SELECT sid, name, logo, category_id, url, ext, cmd FROM vod WHERE $where", args).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentVod(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getString(4), c.getStringOrNull(5), c.getStringOrNull(6)))
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
            "SELECT series_sid, episode_sid, season, episode_num, title, logo, url, ext, cmd FROM episodes WHERE playlist_id = ? AND series_sid = ? ORDER BY season, episode_num",
            arrayOf(playlistId, seriesSid.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentEpisode(c.getInt(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4), c.getStringOrNull(5), c.getString(6), c.getStringOrNull(7), c.getStringOrNull(8)))
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
        db.rawQuery("SELECT sid, name, logo, tvg_id, category_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing FROM channels WHERE playlist_id = ? AND sid = ?", arrayOf(playlistId, sid.toString())).use { c ->
            if (c.moveToFirst()) ContentChannel(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getString(5), c.getStringOrNull(6), c.getInt(7) > 0, c.getInt(8) > 0, c.getInt(9) > 0) else null
        }
    }

    /** A single VOD row by sid (with its Stalker cmd) — the cold-start Library play path (P6). */
    suspend fun vodRow(playlistId: String, sid: Int): ContentVod? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT sid, name, logo, category_id, url, ext, cmd FROM vod WHERE playlist_id = ? AND sid = ?", arrayOf(playlistId, sid.toString())).use { c ->
            if (c.moveToFirst()) ContentVod(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getString(4), c.getStringOrNull(5), c.getStringOrNull(6)) else null
        }
    }

    /**
     * Replaces one playlist's LIVE lineup + live categories in a single transaction, leaving the
     * VOD/series write-through rows and the EPG untouched — the Stalker mirror path (P6): the
     * whole lineup arrives in one get_all_channels, so it refreshes wholesale, while VOD only ever
     * accumulates page by page. built_at doubles as the lineup freshness marker (Stalker playlists
     * never run the M3U ingest); epg_built_at is preserved so a lineup refresh doesn't force an
     * EPG re-fetch.
     */
    suspend fun replaceLiveLineup(
        playlistId: String,
        channels: List<ContentChannel>,
        categories: List<Pair<String, String>>, // (id, name), type = live
    ) = withContext(Dispatchers.IO) {
        inTx {
            db.delete("channels", "playlist_id = ?", arrayOf(playlistId))
            db.delete("categories", "playlist_id = ? AND type = ?", arrayOf(playlistId, TYPE_LIVE))
            val s = db.compileStatement("INSERT OR REPLACE INTO channels(playlist_id, category_id, sid, name, logo, tvg_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
            for (r in channels) {
                s.clearBindings()
                s.bindString(1, playlistId); bindNullable(s, 2, r.categoryId); s.bindLong(3, r.sid.toLong())
                s.bindString(4, r.name); bindNullable(s, 5, r.logo); bindNullable(s, 6, r.tvgId); s.bindString(7, r.url)
                bindNullable(s, 8, r.cmd); s.bindLong(9, if (r.hasArchive) 1L else 0L)
                s.bindLong(10, if (r.useHttpTmpLink) 1L else 0L); s.bindLong(11, if (r.useLoadBalancing) 1L else 0L)
                s.executeInsert()
            }
            s.close()
            val cs = db.compileStatement("INSERT OR REPLACE INTO categories(playlist_id, type, id, name) VALUES(?,?,?,?)")
            for ((id, name) in categories) {
                cs.clearBindings()
                cs.bindString(1, playlistId); cs.bindString(2, TYPE_LIVE); cs.bindString(3, id); cs.bindString(4, name)
                cs.executeInsert()
            }
            cs.close()
            // Freshness LAST, in the same tx; UPDATE-then-INSERT keeps epg_built_at/tvg_url intact.
            val updated = db.compileStatement("UPDATE ingest_meta SET built_at = ?, live_count = ? WHERE playlist_id = ?").let { u ->
                u.bindLong(1, System.currentTimeMillis()); u.bindLong(2, channels.size.toLong()); u.bindString(3, playlistId)
                u.executeUpdateDelete().also { u.close() }
            }
            if (updated == 0) {
                db.execSQL(
                    "INSERT INTO ingest_meta(playlist_id, built_at, live_count, vod_count, series_count, tvg_url, epg_built_at) VALUES(?,?,?,0,0,NULL,NULL)",
                    arrayOf<Any?>(playlistId, System.currentTimeMillis(), channels.size)
                )
            }
        }
    }

    /**
     * Best-effort write-through upsert of browsed Stalker rows (P6) — anything the user has EVER
     * seen stays playable after a cold start. Small batches (a browse page), one transaction.
     */
    suspend fun upsertStalkerRows(
        playlistId: String,
        vod: List<ContentVod> = emptyList(),
        series: List<ContentSeries> = emptyList(),
        episodes: List<ContentEpisode> = emptyList(),
    ) = withContext(Dispatchers.IO) {
        if (vod.isEmpty() && series.isEmpty() && episodes.isEmpty()) return@withContext
        inTx {
            if (vod.isNotEmpty()) {
                val s = db.compileStatement("INSERT OR REPLACE INTO vod(playlist_id, category_id, sid, name, logo, url, ext, cmd) VALUES(?,?,?,?,?,?,?,?)")
                for (r in vod) {
                    s.clearBindings()
                    s.bindString(1, playlistId); bindNullable(s, 2, r.categoryId); s.bindLong(3, r.sid.toLong())
                    s.bindString(4, r.name); bindNullable(s, 5, r.logo); s.bindString(6, r.url); bindNullable(s, 7, r.ext)
                    bindNullable(s, 8, r.cmd)
                    s.executeInsert()
                }
                s.close()
            }
            if (series.isNotEmpty()) {
                val s = db.compileStatement("INSERT OR REPLACE INTO series(playlist_id, category_id, sid, name, logo) VALUES(?,?,?,?,?)")
                for (r in series) {
                    s.clearBindings()
                    s.bindString(1, playlistId); bindNullable(s, 2, r.categoryId); s.bindLong(3, r.sid.toLong())
                    s.bindString(4, r.name); bindNullable(s, 5, r.logo)
                    s.executeInsert()
                }
                s.close()
            }
            if (episodes.isNotEmpty()) {
                val s = db.compileStatement("INSERT OR REPLACE INTO episodes(playlist_id, series_sid, episode_sid, season, episode_num, title, logo, url, ext, cmd) VALUES(?,?,?,?,?,?,?,?,?,?)")
                for (r in episodes) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, r.seriesSid.toLong()); s.bindString(3, r.episodeSid)
                    s.bindLong(4, r.season.toLong()); s.bindLong(5, r.episodeNum.toLong()); s.bindString(6, r.title)
                    bindNullable(s, 7, r.logo); s.bindString(8, r.url); bindNullable(s, 9, r.ext); bindNullable(s, 10, r.cmd)
                    s.executeInsert()
                }
                s.close()
            }
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
        // A wholesale refresh supersedes the per-channel fetch stamps too.
        inTx {
            db.delete("epg_programmes", "playlist_id = ?", arrayOf(playlistId))
            db.delete("epg_channel_fetch", "playlist_id = ?", arrayOf(playlistId))
        }
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
                val s = db.compileStatement("INSERT INTO epg_programmes(playlist_id, channel_id, start_ms, end_ms, title, desc, has_archive) VALUES(?,?,?,?,?,?,?)")
                for (p in batch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindString(2, p.channelId)
                    s.bindLong(3, p.startMs); s.bindLong(4, p.endMs); s.bindString(5, p.title)
                    bindNullable(s, 6, p.desc)
                    s.bindLong(7, if (p.hasArchive) 1L else 0L)
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
    /**
     * Programmes in a time window whose title or description mentions any of [tokens].
     *
     * The provider's own guide, searched in BULK — the counterpart to the mirror's
     * programmesInWindow. Sports matching previously had no way to ask "which of my channels
     * is showing this match?" of the provider's EPG: the only entry point was a per-channel
     * lookup, so the matcher fell back to one get_short_epg network call per channel and had
     * to gate that behind a channel-NAME filter to stay affordable. A channel whose name says
     * nothing useful ("BG: Diema Sport 2") was therefore never asked, even when this table
     * already knew it was airing the fixture.
     *
     * Bounded by the window (a few hours), so the scan stays small even on a 26k-channel panel.
     */
    suspend fun epgSearch(
        playlistId: String,
        tokens: List<String>,
        fromMs: Long,
        toMs: Long,
        limit: Int = 400,
    ): List<EpgProgramme> = withContext(Dispatchers.IO) {
        if (tokens.isEmpty()) return@withContext emptyList()
        val terms = tokens.take(8)
        val where = terms.joinToString(" OR ") { "(lower(title) LIKE ? OR lower(coalesce(desc,'')) LIKE ?)" }
        val args = buildList {
            add(playlistId); add(toMs.toString()); add(fromMs.toString())
            terms.forEach { add("%${it.lowercase()}%"); add("%${it.lowercase()}%") }
            add(limit.toString())
        }.toTypedArray()
        db.rawQuery(
            "SELECT channel_id, start_ms, end_ms, title, desc, has_archive FROM epg_programmes " +
                "WHERE playlist_id = ? AND start_ms < ? AND end_ms > ? AND ($where) " +
                "ORDER BY start_ms LIMIT ?",
            args,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(EpgProgramme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getStringOrNull(4), c.getInt(5) > 0))
                }
            }
        }
    }

    suspend fun epgNowNext(playlistId: String, channelId: String, nowMs: Long): List<EpgProgramme> = withContext(Dispatchers.IO) {
        // The current programme (latest one that started at/before now and hasn't ended) + the next.
        // A single query: everything ending after now, ordered by start, take 2. The first is "now"
        // if it already started, else the schedule has a gap and it's the upcoming programme.
        db.rawQuery(
            "SELECT channel_id, start_ms, end_ms, title, desc, has_archive FROM epg_programmes WHERE playlist_id = ? AND channel_id = ? AND end_ms > ? ORDER BY start_ms LIMIT 2",
            arrayOf(playlistId, channelId, nowMs.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(EpgProgramme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getStringOrNull(4), c.getInt(5) > 0))
            }
        }
    }

    /**
     * Windowed guide read: programmes overlapping [fromMs, toMs) for one channel, ordered by
     * start, desc truncated to its first 600 chars (SUBSTR runs in SQLite, so a feed's 4KB
     * synopsis never lands in the heap — [epgFullDesc] fetches the whole text on demand).
     * [limit] keeps a corrupt feed from materializing thousands of rows.
     */
    suspend fun epgWindow(
        playlistId: String,
        channelId: String,
        fromMs: Long,
        toMs: Long,
        limit: Int = 200,
    ): List<EpgProgramme> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT channel_id, start_ms, end_ms, title, SUBSTR(desc, 1, 600), has_archive FROM epg_programmes " +
                "WHERE playlist_id = ? AND channel_id = ? AND start_ms < ? AND end_ms > ? ORDER BY start_ms LIMIT ?",
            arrayOf(playlistId, channelId, toMs.toString(), fromMs.toString(), limit.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(EpgProgramme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), c.getStringOrNull(4), c.getInt(5) > 0))
            }
        }
    }

    /** The FULL description of one programme (keyed by its start) — the details sheet's lazy read. */
    suspend fun epgFullDesc(playlistId: String, channelId: String, startMs: Long): String? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT desc FROM epg_programmes WHERE playlist_id = ? AND channel_id = ? AND start_ms = ? LIMIT 1",
            arrayOf(playlistId, channelId, startMs.toString())
        ).use { c ->
            if (c.moveToFirst()) c.getStringOrNull(0) else null
        }
    }

    /**
     * Atomic per-channel EPG refill: the channel's old rows are DELETEd in the SAME transaction
     * as the new batch's insert, and the (playlist, channel) fetch stamp is written with them —
     * a reader never sees an empty channel mid-refill and a crash leaves the old rows intact.
     * Rows are stored under [channelId] regardless of what their own field says: the refill is
     * per-channel by contract. An empty [programmes] still stamps [fetchedAtMs] so the guide's
     * lazy-fetch gate stops re-asking a channel the provider has no guide for.
     */
    suspend fun refillChannelEpg(
        playlistId: String,
        channelId: String,
        programmes: List<EpgProgramme>,
        fetchedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        inTx {
            db.delete("epg_programmes", "playlist_id = ? AND channel_id = ?", arrayOf(playlistId, channelId))
            if (programmes.isNotEmpty()) {
                val s = db.compileStatement("INSERT INTO epg_programmes(playlist_id, channel_id, start_ms, end_ms, title, desc, has_archive) VALUES(?,?,?,?,?,?,?)")
                for (p in programmes) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindString(2, channelId)
                    s.bindLong(3, p.startMs); s.bindLong(4, p.endMs); s.bindString(5, p.title)
                    bindNullable(s, 6, p.desc)
                    s.bindLong(7, if (p.hasArchive) 1L else 0L)
                    s.executeInsert()
                }
                s.close()
            }
            db.execSQL(
                "INSERT OR REPLACE INTO epg_channel_fetch(playlist_id, channel_id, fetched_at) VALUES(?,?,?)",
                arrayOf<Any?>(playlistId, channelId, fetchedAtMs)
            )
        }
    }

    /** When this channel's EPG was last refilled (null = never — the lazy-fetch gate opens). */
    suspend fun epgChannelFetchedAt(playlistId: String, channelId: String): Long? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT fetched_at FROM epg_channel_fetch WHERE playlist_id = ? AND channel_id = ?",
            arrayOf(playlistId, channelId)
        ).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    /** Drops programmes that ended before [cutoffMs] — the guide never reads that far back. */
    suspend fun pruneEpg(playlistId: String, cutoffMs: Long) = withContext(Dispatchers.IO) {
        inTx { db.delete("epg_programmes", "playlist_id = ? AND end_ms < ?", arrayOf(playlistId, cutoffMs.toString())) }
    }

    /**
     * Forgets when this playlist's channels were last refilled, WITHOUT touching their rows.
     *
     * The guide-offset setting (fix 2) needs this: stored programmes were corrected under the OLD
     * offset, and the six-hour fetch gate would otherwise keep showing them long after the user
     * changed the setting to fix exactly what they are looking at. Open stamps make the next focus
     * refetch-and-replace per channel; the stale rows stay readable until then — the same
     * no-gap trade [clear] makes for the catalog.
     */
    suspend fun resetEpgFetchStamps(playlistId: String) = withContext(Dispatchers.IO) {
        inTx { db.delete("epg_channel_fetch", "playlist_id = ?", arrayOf(playlistId)) }
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
