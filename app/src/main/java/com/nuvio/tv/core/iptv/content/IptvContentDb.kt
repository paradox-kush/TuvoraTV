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
    // v5 (Overlay Build Spec v1.3.3 §5, the generation swap): every catalog table gains a
    // `generation` column in its primary key, so a refresh builds generation N+1 BESIDE the one
    // still being served and flips in one transaction (IngestWriter.finish). A crash before the
    // flip leaves the previous, complete catalog serving — the old clear-first ingest left it empty.
    private val helper = object : SQLiteOpenHelper(context, "iptv_content.db", null, 5) {
        override fun onConfigure(db: SQLiteDatabase) {
            // WAL: browse/guide reads keep serving while a background ingest writes — the
            // default journal mode blocks every reader for the duration of each write
            // transaction, which on a budget box turns a catalog/EPG ingest into visible
            // UI stalls. NORMAL sync is safe: this whole DB is a re-ingestable cache.
            db.enableWriteAheadLogging()
            db.execSQL("PRAGMA synchronous=NORMAL")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE channels(playlist_id TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0, category_id TEXT, sid INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, tvg_id TEXT, url TEXT NOT NULL, cmd TEXT, tv_archive INTEGER, use_http_tmp_link INTEGER, use_load_balancing INTEGER, PRIMARY KEY(playlist_id, generation, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX channels_cat ON channels(playlist_id, generation, category_id)")
            db.execSQL("CREATE TABLE vod(playlist_id TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0, category_id TEXT, sid INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, ext TEXT, cmd TEXT, PRIMARY KEY(playlist_id, generation, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX vod_cat ON vod(playlist_id, generation, category_id)")
            db.execSQL("CREATE TABLE series(playlist_id TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0, category_id TEXT, sid INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, PRIMARY KEY(playlist_id, generation, sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX series_cat ON series(playlist_id, generation, category_id)")
            db.execSQL("CREATE TABLE episodes(playlist_id TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0, series_sid INTEGER NOT NULL, episode_sid TEXT NOT NULL, season INTEGER NOT NULL, episode_num INTEGER NOT NULL, title TEXT NOT NULL, logo TEXT, url TEXT NOT NULL, ext TEXT, cmd TEXT, PRIMARY KEY(playlist_id, generation, episode_sid)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX episodes_series ON episodes(playlist_id, generation, series_sid)")
            db.execSQL("CREATE TABLE categories(playlist_id TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0, type TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(playlist_id, generation, type, id)) WITHOUT ROWID")
            // tvg_url = the url-tvg/x-tvg-url captured from the #EXTM3U header (default XMLTV source);
            // epg_built_at = when this playlist's EPG was last fetched (throttles the ~2×/day refresh);
            // active_generation = the generation readers see (the last COMPLETE build's).
            db.execSQL("CREATE TABLE ingest_meta(playlist_id TEXT NOT NULL PRIMARY KEY, built_at INTEGER NOT NULL, live_count INTEGER NOT NULL, vod_count INTEGER NOT NULL, series_count INTEGER NOT NULL, tvg_url TEXT, epg_built_at INTEGER, active_generation INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID")
            createEpgTable(db)
            db.execSQL(EPG_META_DDL)
            db.execSQL(ID_SCHEME_DDL)
            db.execSQL(LEGACY_IDS_DDL)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Everything here is a rebuildable cache of the parsed playlist — drop + re-ingest.
            for (t in listOf("channels", "vod", "series", "episodes", "categories", "ingest_meta", "epg_programmes", "epg_channel_fetch", "epg_meta")) {
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

    // epg_meta is created lazily too, so existing v5 databases pick it up with no migration (a version
    // bump would drop every cached catalog).
    private val db: SQLiteDatabase by lazy {
        helper.writableDatabase.also {
            it.execSQL(EPG_META_DDL)
            // B64: created lazily like epg_meta — a version bump would drop every cached catalog, and
            // the old catalog is exactly what the legacy-id capture must read.
            it.execSQL(ID_SCHEME_DDL)
            it.execSQL(LEGACY_IDS_DDL)
            // B64 follow-up: ids are hashes now, so the source order needs its own column (rows read
            // back in key order). Added in place, like the tables above; NULL on older rows = sid order.
            for (table in ORDERED_TABLES) {
                val has = it.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    var found = false
                    while (c.moveToNext()) if (c.getString(1) == "ord") found = true
                    found
                }
                if (!has) it.execSQL("ALTER TABLE $table ADD COLUMN ord INTEGER")
            }
        }
    }

    /** The generation an in-flight ingest (ingest → writer → finish) is writing, per playlist. */
    private val pendingGeneration = HashMap<String, Long>()

    private val catalogTables = listOf("channels", "vod", "series", "episodes", "categories")

    /**
     * SQL predicate selecting the served generation — the last COMPLETE build's (0 before any build,
     * which is also where Stalker's write-through rows live). Every query using it binds the playlist
     * id a second time, right after the first.
     */
    private val gen = "generation = COALESCE((SELECT active_generation FROM ingest_meta WHERE playlist_id = ?), 0)"

    private fun activeGeneration(playlistId: String): Long =
        db.rawQuery("SELECT active_generation FROM ingest_meta WHERE playlist_id = ?", arrayOf(playlistId)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

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

    /**
     * When this playlist's EPG was last fetched (null = never — refresh it). epg_meta holds the stamp
     * for every playlist; ingest_meta's column is the pre-epg_meta stamp, read so an upgrade does not
     * refetch every guide once.
     */
    suspend fun epgBuiltAt(playlistId: String): Long? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT COALESCE((SELECT epg_built_at FROM epg_meta WHERE playlist_id = ?), " +
                "(SELECT epg_built_at FROM ingest_meta WHERE playlist_id = ?))",
            arrayOf(playlistId, playlistId),
        ).use { c ->
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
     * Series are grouped by header: [addEpisode] writes the header row on first sight of its sid.
     */
    inner class IngestWriter internal constructor(private val playlistId: String, private val generation: Long) {
        private val counts = Counts()
        private val channelBatch = ArrayList<ContentChannel>(CHUNK)
        private val vodBatch = ArrayList<ContentVod>(CHUNK)
        private val seriesBatch = ArrayList<ContentSeries>(CHUNK)
        private val episodeBatch = ArrayList<ContentEpisode>(CHUNK)
        private val categoryBatch = ArrayList<Triple<String, String, String>>()  // type, id, name
        private val seenCategories = HashSet<String>()   // "type|id"
        private val seenSeries = HashSet<Int>()   // series sids whose header row is written
        private var tvgUrl: String? = null   // url-tvg/x-tvg-url from the #EXTM3U header, if any
        // The row's position in the source (B64 follow-up: the ids no longer carry it).
        private var nextOrd = 0L
        private val channelOrd = ArrayList<Long>(CHUNK)
        private val vodOrd = ArrayList<Long>(CHUNK)
        private val seriesOrd = ArrayList<Long>(CHUNK)

        /** Capture the M3U header's default XMLTV EPG url (persisted with the meta row). */
        fun setTvgUrl(url: String) { if (tvgUrl == null && url.isNotBlank()) tvgUrl = url }

        /** [categoryName] = the display name of [ContentChannel.categoryId] (defaults to the id itself). */
        fun addChannel(row: ContentChannel, categoryName: String? = row.categoryId) {
            channelBatch.add(row); channelOrd.add(nextOrd++); counts.live++
            categoryOf(TYPE_LIVE, row.categoryId, categoryName)
            if (channelBatch.size >= CHUNK) flushChannels()
        }

        fun addVod(row: ContentVod, categoryName: String? = row.categoryId) {
            vodBatch.add(row); vodOrd.add(nextOrd++); counts.vod++
            categoryOf(TYPE_VOD, row.categoryId, categoryName)
            if (vodBatch.size >= CHUNK) flushVod()
        }

        /**
         * B64: an episode under its series header, both with their ids already decided
         * ([com.nuvio.tv.core.iptv.M3uIngestMapping]); the header row is written on first sight of
         * its sid. Ids are content-derived now, so a repeated id (the same stream listed twice)
         * replaces its row instead of being numbered apart.
         */
        fun addEpisode(series: ContentSeries, episode: ContentEpisode, categoryName: String? = series.categoryId) {
            if (seenSeries.add(series.sid)) {
                seriesBatch.add(series); seriesOrd.add(nextOrd++); counts.series++
                categoryOf(TYPE_SERIES, series.categoryId, categoryName)
                if (seriesBatch.size >= CHUNK) flushSeries()
            }
            episodeBatch.add(episode)
            if (episodeBatch.size >= CHUNK) flushEpisodes()
        }

        private fun categoryOf(type: String, id: String?, name: String?) {
            val catId = id ?: return
            if (seenCategories.add("$type|$catId")) categoryBatch.add(Triple(type, catId, name ?: catId))
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
                val s = db.compileStatement("INSERT OR REPLACE INTO channels(playlist_id, generation, category_id, sid, name, logo, tvg_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing, ord) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")
                for ((i, r) in channelBatch.withIndex()) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, generation); bindNullable(s, 3, r.categoryId); s.bindLong(4, r.sid.toLong())
                    s.bindString(5, r.name); bindNullable(s, 6, r.logo); bindNullable(s, 7, r.tvgId); s.bindString(8, r.url)
                    bindNullable(s, 9, r.cmd); s.bindLong(10, if (r.hasArchive) 1L else 0L)
                    s.bindLong(11, if (r.useHttpTmpLink) 1L else 0L); s.bindLong(12, if (r.useLoadBalancing) 1L else 0L)
                    s.bindLong(13, channelOrd[i])
                    s.executeInsert()
                }
                s.close()
            }
            channelBatch.clear(); channelOrd.clear()
        }

        private fun flushVod() {
            inTx {
                val s = db.compileStatement("INSERT OR REPLACE INTO vod(playlist_id, generation, category_id, sid, name, logo, url, ext, cmd, ord) VALUES(?,?,?,?,?,?,?,?,?,?)")
                for ((i, r) in vodBatch.withIndex()) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, generation); bindNullable(s, 3, r.categoryId); s.bindLong(4, r.sid.toLong())
                    s.bindString(5, r.name); bindNullable(s, 6, r.logo); s.bindString(7, r.url); bindNullable(s, 8, r.ext)
                    bindNullable(s, 9, r.cmd)
                    s.bindLong(10, vodOrd[i])
                    s.executeInsert()
                }
                s.close()
            }
            vodBatch.clear(); vodOrd.clear()
        }

        private fun flushSeries() {
            inTx {
                val s = db.compileStatement("INSERT OR REPLACE INTO series(playlist_id, generation, category_id, sid, name, logo, ord) VALUES(?,?,?,?,?,?,?)")
                for ((i, r) in seriesBatch.withIndex()) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, generation); bindNullable(s, 3, r.categoryId); s.bindLong(4, r.sid.toLong())
                    s.bindString(5, r.name); bindNullable(s, 6, r.logo)
                    s.bindLong(7, seriesOrd[i])
                    s.executeInsert()
                }
                s.close()
            }
            seriesBatch.clear(); seriesOrd.clear()
        }

        private fun flushEpisodes() {
            inTx {
                val s = db.compileStatement("INSERT OR REPLACE INTO episodes(playlist_id, generation, series_sid, episode_sid, season, episode_num, title, logo, url, ext, cmd) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                for (r in episodeBatch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, generation); s.bindLong(3, r.seriesSid.toLong()); s.bindString(4, r.episodeSid)
                    s.bindLong(5, r.season.toLong()); s.bindLong(6, r.episodeNum.toLong()); s.bindString(7, r.title)
                    bindNullable(s, 8, r.logo); s.bindString(9, r.url); bindNullable(s, 10, r.ext); bindNullable(s, 11, r.cmd)
                    s.executeInsert()
                }
                s.close()
            }
            episodeBatch.clear()
        }

        private fun flushCategories() {
            inTx {
                val s = db.compileStatement("INSERT OR REPLACE INTO categories(playlist_id, generation, type, id, name) VALUES(?,?,?,?,?)")
                for ((type, id, name) in categoryBatch) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, generation); s.bindString(3, type); s.bindString(4, id); s.bindString(5, name)
                    s.executeInsert()
                }
                s.close()
            }
            categoryBatch.clear()
        }

        /**
         * The flip: flush remaining batches, then in ONE transaction point readers at this
         * generation (the meta row, written LAST, is still the "ingest complete" signal) and drop
         * every other generation's rows. epg_built_at is left null so a fresh ingest re-fetches the
         * EPG (the catalog's channel set may have changed); the old programmes stay readable until
         * that fetch replaces them.
         */
        internal fun finish() {
            flushAll()
            inTx {
                db.execSQL(
                    "INSERT OR REPLACE INTO ingest_meta(playlist_id, built_at, live_count, vod_count, series_count, tvg_url, epg_built_at, active_generation) VALUES(?,?,?,?,?,?,NULL,?)",
                    arrayOf<Any?>(playlistId, System.currentTimeMillis(), counts.live, counts.vod, counts.series, tvgUrl, generation)
                )
                // The guide freshness goes stale with the catalog (see the NULL above).
                db.delete("epg_meta", "playlist_id = ?", arrayOf(playlistId))
                for (t in catalogTables) db.delete(t, "playlist_id = ? AND generation <> ?", arrayOf(playlistId, generation.toString()))
            }
            pendingGeneration.remove(playlistId)
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
     * Runs a full ingest without ever touching what is being served: the new rows land at
     * `active_generation + 1` while readers keep the last complete build, and [IngestWriter.finish]
     * flips them in one transaction. Rows a previous attempt left at a non-active generation (a
     * crash before its flip) are purged first. A crash before [finish] leaves the previous catalog
     * serving and [builtAt] untouched; the next scheduled refresh simply retries.
     */
    suspend fun ingest(playlistId: String, fill: suspend (IngestWriter) -> Unit): IngestWriter = withContext(Dispatchers.IO) {
        val generation = inTxReturning {
            val active = activeGeneration(playlistId)
            for (t in catalogTables) db.delete(t, "playlist_id = ? AND generation <> ?", arrayOf(playlistId, active.toString()))
            active + 1
        }
        pendingGeneration[playlistId] = generation
        val writer = IngestWriter(playlistId, generation)
        fill(writer)
        writer.finish()
        writer
    }

    suspend fun clear(playlistId: String) = withContext(Dispatchers.IO) {
        inTx {
            // NOTE: epg_programmes is intentionally NOT cleared here. A catalog re-ingest resets the
            // meta's epg_built_at (finish writes NULL) so the EPG re-fetches, but the old programmes
            // stay readable until that fetch replaces them (via replaceEpg) — no now/next gap.
            // B64: id_scheme goes with the catalog; the kept legacy ids do NOT (an edit re-ingests under
            // the same id and the profile may not have applied them yet) — only [purge] drops them.
            for (t in listOf("channels", "vod", "series", "episodes", "categories", "ingest_meta", "epg_meta", "id_scheme")) {
                db.delete(t, "playlist_id = ?", arrayOf(playlistId))
            }
        }
        pendingGeneration.remove(playlistId)
    }

    private inline fun <T> inTxReturning(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Full removal (playlist deleted): [clear] plus its EPG rows — nothing left on disk, including the
     * shadow rows of a guide refresh that died mid-fill (otherwise they sit there until the same id is
     * re-added and [replaceEpg] runs for it again).
     */
    suspend fun purge(playlistId: String) {
        clear(playlistId)
        withContext(Dispatchers.IO) {
            db.execSQL(EPG_SHADOW_DDL) // lazily created (see replaceEpg) — may not exist yet
            inTx {
                db.delete("epg_programmes", "playlist_id = ?", arrayOf(playlistId))
                db.delete("epg_channel_fetch", "playlist_id = ?", arrayOf(playlistId))
                db.delete(EPG_SHADOW, "playlist_id = ?", arrayOf(playlistId))
                db.delete("m3u_legacy_ids", "playlist_id = ?", arrayOf(playlistId))
            }
        }
    }

    // --- B64: item-id scheme + legacy ids -------------------------------------

    /** The item-id scheme the served catalog was built with (1 = pre-B64 ordinals; none built = 1). */
    suspend fun idScheme(playlistId: String): Int = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT scheme FROM id_scheme WHERE playlist_id = ?", arrayOf(playlistId)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 1
        }
    }

    suspend fun setIdScheme(playlistId: String, scheme: Int) = withContext(Dispatchers.IO) {
        db.execSQL("INSERT OR REPLACE INTO id_scheme(playlist_id, scheme) VALUES(?,?)", arrayOf<Any?>(playlistId, scheme))
    }

    /**
     * B64: maps every row of the SERVED (pre-B64) catalog through [com.nuvio.tv.core.iptv.M3uLegacyIds]
     * and keeps the old -> new pairs, BEFORE a login-free ingest replaces the catalog (the old ordinal
     * ids exist nowhere else). Paged reads, chunked writes; replaces any earlier capture. Returns the
     * number of pairs kept.
     */
    suspend fun captureLegacyIds(playlistId: String, login: com.nuvio.tv.core.iptv.identity.M3uIdentity.Login?): Int = withContext(Dispatchers.IO) {
        val L = com.nuvio.tv.core.iptv.M3uLegacyIds
        val seriesById = HashMap<Int, ContentSeries>()
        db.rawQuery("SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? AND $gen", arrayOf(playlistId, playlistId)).use { c ->
            while (c.moveToNext()) ContentSeries(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3)).let { seriesById[it.sid] = it }
        }
        val moves = sequence {
            for (type in listOf(TYPE_LIVE, TYPE_VOD, TYPE_SERIES)) {
                db.rawQuery("SELECT id, name FROM categories WHERE playlist_id = ? AND $gen AND type = ?", arrayOf(playlistId, playlistId, type)).use { c ->
                    while (c.moveToNext()) yield(L.category(type, ContentCategory(c.getString(0), c.getString(1))))
                }
            }
            db.rawQuery("SELECT sid, name, logo, tvg_id, category_id, url FROM channels WHERE playlist_id = ? AND $gen", arrayOf(playlistId, playlistId)).use { c ->
                while (c.moveToNext()) L.channel(ContentChannel(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getString(5)), login)?.let { yield(it) }
            }
            db.rawQuery("SELECT sid, name, logo, category_id, url, ext FROM vod WHERE playlist_id = ? AND $gen", arrayOf(playlistId, playlistId)).use { c ->
                while (c.moveToNext()) L.movie(ContentVod(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getString(4), c.getStringOrNull(5)), login)?.let { yield(it) }
            }
            db.rawQuery("SELECT series_sid, episode_sid, season, episode_num, title, logo, url, ext FROM episodes WHERE playlist_id = ? AND $gen", arrayOf(playlistId, playlistId)).use { c ->
                while (c.moveToNext()) {
                    val e = ContentEpisode(c.getInt(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4), c.getStringOrNull(5), c.getString(6), c.getStringOrNull(7))
                    yieldAll(L.episode(e, seriesById[e.seriesSid], login))
                }
            }
        }
        // ONE transaction: the reads stream through cursors on the same connection the inserts use, so
        // nothing larger than one row is held (a 100k-item catalog never materializes), and a crash
        // leaves no half capture.
        var kept = 0
        inTx {
            db.delete("m3u_legacy_ids", "playlist_id = ?", arrayOf(playlistId))
            val st = db.compileStatement("INSERT OR IGNORE INTO m3u_legacy_ids(playlist_id, old_id, new_id, series_id, season, episode, series_name) VALUES(?,?,?,?,?,?,?)")
            for (m in moves) {
                if (m.oldId == m.newId) continue
                st.clearBindings()
                st.bindString(1, playlistId); st.bindString(2, m.oldId); st.bindString(3, m.newId)
                bindNullable(st, 4, m.seriesId)
                if (m.season != null) st.bindLong(5, m.season.toLong()) else st.bindNull(5)
                if (m.episode != null) st.bindLong(6, m.episode.toLong()) else st.bindNull(6)
                bindNullable(st, 7, m.seriesName)
                // INSERT OR IGNORE: the first move per old id wins (a series maps to its first episode's series).
                kept += st.executeUpdateDelete()
            }
        }
        kept
    }

    /** B64: the kept legacy moves of [playlistId] for the given old suffixes (indexed lookups, chunked). */
    suspend fun legacyIds(playlistId: String, oldIds: Collection<String>): Map<String, com.nuvio.tv.core.iptv.M3uLegacyIds.Move> = withContext(Dispatchers.IO) {
        val out = HashMap<String, com.nuvio.tv.core.iptv.M3uLegacyIds.Move>()
        oldIds.distinct().chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT old_id, new_id, series_id, season, episode, series_name FROM m3u_legacy_ids WHERE playlist_id = ? AND old_id IN ($marks)",
                arrayOf(playlistId) + chunk.toTypedArray(),
            ).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = com.nuvio.tv.core.iptv.M3uLegacyIds.Move(
                    c.getString(0), c.getString(1), c.getStringOrNull(2),
                    if (c.isNull(3)) null else c.getInt(3), if (c.isNull(4)) null else c.getInt(4), c.getStringOrNull(5),
                )
            }
        }
        out
    }

    /**
     * B64: a Step 0 key adoption renamed the playlist — its kept legacy moves follow (they are id
     * SUFFIXES, valid under any playlist key), so a profile that has not applied them yet still can.
     */
    suspend fun moveLegacyIds(oldPlaylistId: String, newPlaylistId: String) = withContext(Dispatchers.IO) {
        db.execSQL("UPDATE OR IGNORE m3u_legacy_ids SET playlist_id = ? WHERE playlist_id = ?", arrayOf<Any?>(newPlaylistId, oldPlaylistId))
    }

    /** B64: whether any legacy moves are kept for [playlistId] (a profile may still need to apply them). */
    suspend fun hasLegacyIds(playlistId: String): Boolean = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT 1 FROM m3u_legacy_ids WHERE playlist_id = ? LIMIT 1", arrayOf(playlistId)).use { it.moveToFirst() }
    }

    // --- Queries ------------------------------------------------------------

    suspend fun categoriesFor(playlistId: String, type: String): List<ContentCategory> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT id, name FROM categories WHERE playlist_id = ? AND $gen AND type = ? ORDER BY name", arrayOf(playlistId, playlistId, type)).use { c ->
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
        db.rawQuery("SELECT sid, name, logo, tvg_id, category_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing FROM channels WHERE $where ORDER BY ord, sid", args).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentChannel(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getString(5), c.getStringOrNull(6), c.getInt(7) > 0, c.getInt(8) > 0, c.getInt(9) > 0))
            }
        }
    }

    suspend fun vodFor(playlistId: String, categoryId: String?): List<ContentVod> = withContext(Dispatchers.IO) {
        val (where, args) = catFilter(playlistId, categoryId)
        db.rawQuery("SELECT sid, name, logo, category_id, url, ext, cmd FROM vod WHERE $where ORDER BY ord, sid", args).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentVod(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getString(4), c.getStringOrNull(5), c.getStringOrNull(6)))
            }
        }
    }

    suspend fun seriesFor(playlistId: String, categoryId: String?): List<ContentSeries> = withContext(Dispatchers.IO) {
        val (where, args) = catFilter(playlistId, categoryId)
        db.rawQuery("SELECT sid, name, logo, category_id FROM series WHERE $where ORDER BY ord, sid", args).use { c ->
            buildList { while (c.moveToNext()) add(ContentSeries(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3))) }
        }
    }

    suspend fun episodesFor(playlistId: String, seriesSid: Int): List<ContentEpisode> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT series_sid, episode_sid, season, episode_num, title, logo, url, ext, cmd FROM episodes WHERE playlist_id = ? AND $gen AND series_sid = ? ORDER BY season, episode_num",
            arrayOf(playlistId, playlistId, seriesSid.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(ContentEpisode(c.getInt(0), c.getString(1), c.getInt(2), c.getInt(3), c.getString(4), c.getStringOrNull(5), c.getString(6), c.getStringOrNull(7), c.getStringOrNull(8)))
            }
        }
    }

    /** Direct URL of a single channel (live) — used to rebuild a deep-linked/saved item. */
    suspend fun channelUrl(playlistId: String, sid: Int): String? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT url FROM channels WHERE playlist_id = ? AND $gen AND sid = ?", arrayOf(playlistId, playlistId, sid.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    suspend fun vodUrl(playlistId: String, sid: Int): String? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT url FROM vod WHERE playlist_id = ? AND $gen AND sid = ?", arrayOf(playlistId, playlistId, sid.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    suspend fun channelRow(playlistId: String, sid: Int): ContentChannel? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT sid, name, logo, tvg_id, category_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing FROM channels WHERE playlist_id = ? AND $gen AND sid = ?", arrayOf(playlistId, playlistId, sid.toString())).use { c ->
            if (c.moveToFirst()) ContentChannel(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getString(5), c.getStringOrNull(6), c.getInt(7) > 0, c.getInt(8) > 0, c.getInt(9) > 0) else null
        }
    }

    /** A single VOD row by sid (with its Stalker cmd) — the cold-start Library play path (P6). */
    suspend fun vodRow(playlistId: String, sid: Int): ContentVod? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT sid, name, logo, category_id, url, ext, cmd FROM vod WHERE playlist_id = ? AND $gen AND sid = ?", arrayOf(playlistId, playlistId, sid.toString())).use { c ->
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
            // The Stalker lineup lives at the served generation and is replaced in place — this
            // transaction is already atomic, so no generation flip is needed here.
            val g = activeGeneration(playlistId)
            db.delete("channels", "playlist_id = ? AND generation = ?", arrayOf(playlistId, g.toString()))
            db.delete("categories", "playlist_id = ? AND generation = ? AND type = ?", arrayOf(playlistId, g.toString(), TYPE_LIVE))
            val s = db.compileStatement("INSERT OR REPLACE INTO channels(playlist_id, generation, category_id, sid, name, logo, tvg_id, url, cmd, tv_archive, use_http_tmp_link, use_load_balancing) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
            for (r in channels) {
                s.clearBindings()
                s.bindString(1, playlistId); s.bindLong(2, g); bindNullable(s, 3, r.categoryId); s.bindLong(4, r.sid.toLong())
                s.bindString(5, r.name); bindNullable(s, 6, r.logo); bindNullable(s, 7, r.tvgId); s.bindString(8, r.url)
                bindNullable(s, 9, r.cmd); s.bindLong(10, if (r.hasArchive) 1L else 0L)
                s.bindLong(11, if (r.useHttpTmpLink) 1L else 0L); s.bindLong(12, if (r.useLoadBalancing) 1L else 0L)
                s.executeInsert()
            }
            s.close()
            val cs = db.compileStatement("INSERT OR REPLACE INTO categories(playlist_id, generation, type, id, name) VALUES(?,?,?,?,?)")
            for ((id, name) in categories) {
                cs.clearBindings()
                cs.bindString(1, playlistId); cs.bindLong(2, g); cs.bindString(3, TYPE_LIVE); cs.bindString(4, id); cs.bindString(5, name)
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
            // Write-through rows join the served generation (Stalker never runs the M3U ingest).
            val g = activeGeneration(playlistId)
            if (vod.isNotEmpty()) {
                val s = db.compileStatement("INSERT OR REPLACE INTO vod(playlist_id, generation, category_id, sid, name, logo, url, ext, cmd) VALUES(?,?,?,?,?,?,?,?,?)")
                for (r in vod) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, g); bindNullable(s, 3, r.categoryId); s.bindLong(4, r.sid.toLong())
                    s.bindString(5, r.name); bindNullable(s, 6, r.logo); s.bindString(7, r.url); bindNullable(s, 8, r.ext)
                    bindNullable(s, 9, r.cmd)
                    s.executeInsert()
                }
                s.close()
            }
            if (series.isNotEmpty()) {
                val s = db.compileStatement("INSERT OR REPLACE INTO series(playlist_id, generation, category_id, sid, name, logo) VALUES(?,?,?,?,?,?)")
                for (r in series) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, g); bindNullable(s, 3, r.categoryId); s.bindLong(4, r.sid.toLong())
                    s.bindString(5, r.name); bindNullable(s, 6, r.logo)
                    s.executeInsert()
                }
                s.close()
            }
            if (episodes.isNotEmpty()) {
                val s = db.compileStatement("INSERT OR REPLACE INTO episodes(playlist_id, generation, series_sid, episode_sid, season, episode_num, title, logo, url, ext, cmd) VALUES(?,?,?,?,?,?,?,?,?,?,?)")
                for (r in episodes) {
                    s.clearBindings()
                    s.bindString(1, playlistId); s.bindLong(2, g); s.bindLong(3, r.seriesSid.toLong()); s.bindString(4, r.episodeSid)
                    s.bindLong(5, r.season.toLong()); s.bindLong(6, r.episodeNum.toLong()); s.bindString(7, r.title)
                    bindNullable(s, 8, r.logo); s.bindString(9, r.url); bindNullable(s, 10, r.ext); bindNullable(s, 11, r.cmd)
                    s.executeInsert()
                }
                s.close()
            }
        }
    }

    suspend fun seriesRow(playlistId: String, sid: Int): ContentSeries? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? AND $gen AND sid = ?", arrayOf(playlistId, playlistId, sid.toString())).use { c ->
            if (c.moveToFirst()) ContentSeries(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3)) else null
        }
    }

    /** Substring name search within a content type (backs the IPTV rows in Search). */
    suspend fun searchChannels(playlistId: String, query: String, limit: Int): List<ContentChannel> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, logo, tvg_id, category_id, url FROM channels WHERE playlist_id = ? AND $gen AND name LIKE '%' || ? || '%' LIMIT ?",
            arrayOf(playlistId, playlistId, query, limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(ContentChannel(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getStringOrNull(4), c.getString(5))) }
        }
    }

    suspend fun searchVod(playlistId: String, query: String, limit: Int): List<ContentVod> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, logo, category_id, url, ext FROM vod WHERE playlist_id = ? AND $gen AND name LIKE '%' || ? || '%' LIMIT ?",
            arrayOf(playlistId, playlistId, query, limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(ContentVod(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3), c.getString(4), c.getStringOrNull(5))) }
        }
    }

    suspend fun searchSeries(playlistId: String, query: String, limit: Int): List<ContentSeries> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT sid, name, logo, category_id FROM series WHERE playlist_id = ? AND $gen AND name LIKE '%' || ? || '%' LIMIT ?",
            arrayOf(playlistId, playlistId, query, limit.toString())
        ).use { c ->
            buildList { while (c.moveToNext()) add(ContentSeries(c.getInt(0), c.getString(1), c.getStringOrNull(2), c.getStringOrNull(3))) }
        }
    }

    private fun catFilter(playlistId: String, categoryId: String?): Pair<String, Array<String>> =
        if (categoryId == null) "playlist_id = ? AND $gen" to arrayOf(playlistId, playlistId)
        else "playlist_id = ? AND $gen AND category_id = ?" to arrayOf(playlistId, playlistId, categoryId)

    // --- EPG (XMLTV for M3U live now/next) ----------------------------------

    /**
     * The distinct, NORMALIZED (trim+lowercase) EPG channel ids present in this playlist's live
     * channels. The XMLTV parse filters to this set so a 100MB+ guide never fully lands in the DB —
     * only programmes for channels the user actually has are stored.
     */
    suspend fun channelTvgIds(playlistId: String): Set<String> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT DISTINCT tvg_id FROM channels WHERE playlist_id = ? AND $gen AND tvg_id IS NOT NULL", arrayOf(playlistId, playlistId)).use { c ->
            buildSet { while (c.moveToNext()) c.getStringOrNull(0)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { add(it) } }
        }
    }

    /**
     * Replace this playlist's EPG as an ATOMIC generation swap. New programmes stream into a shadow
     * table via [fill] ([EpgWriter] batches [CHUNK]-sized inserts); only once the fill COMPLETES do we
     * flip the live table to the new generation and stamp epg_built_at, all in one transaction.
     *
     * A failed / cancelled / partial fill (network drop, disk error, ingest-scope cancellation) throws
     * out of [fill] BEFORE the swap, so the previous COMPLETE generation keeps serving. The old
     * clear-first-then-fill deleted the guide up front, so any failure left it empty until a later
     * refresh happened to succeed. An empty result (a bad fetch that parsed to nothing) likewise keeps
     * the prior generation, but still advances epg_built_at so we don't refetch the whole ~100MB guide
     * on every browse. Under WAL a reader that races the swap sees the old generation or the new one,
     * never the empty middle. channel_id is stored already-normalized by the caller.
     *
     * Concurrency: whole-guide refresh is single-flight per playlist upstream ([XmltvClient]), so the
     * shadow only ever holds one in-flight generation for a playlist; any rows a previously aborted
     * attempt left behind are cleared before this fill begins.
     */
    suspend fun replaceEpg(playlistId: String, builtAtMs: Long, fill: suspend (EpgWriter) -> Unit) = withContext(Dispatchers.IO) {
        db.execSQL(EPG_SHADOW_DDL) // lazily created so existing v5 databases pick it up with no migration
        inTx { db.delete(EPG_SHADOW, "playlist_id = ?", arrayOf(playlistId)) }

        val writer = EpgWriter(playlistId) // writes into the shadow, never the live table
        fill(writer)                       // may throw / be cancelled -> we never reach the swap below
        writer.flush()

        inTx {
            if (writer.count > 0) {
                // A wholesale refresh supersedes the per-channel fetch stamps too.
                db.delete("epg_programmes", "playlist_id = ?", arrayOf(playlistId))
                db.delete("epg_channel_fetch", "playlist_id = ?", arrayOf(playlistId))
                db.execSQL(
                    "INSERT INTO epg_programmes(playlist_id, channel_id, start_ms, end_ms, title, desc, has_archive) " +
                        "SELECT playlist_id, channel_id, start_ms, end_ms, title, desc, has_archive FROM $EPG_SHADOW WHERE playlist_id = ?",
                    arrayOf(playlistId),
                )
            }
            db.delete(EPG_SHADOW, "playlist_id = ?", arrayOf(playlistId))
            // Stamp freshness last — even on an empty result, so a provider serving no guide right now
            // doesn't refetch on every browse. An upsert into epg_meta, because an Xtream playlist has
            // no ingest_meta row (its lineup lives in XtreamMatchIndex): the old UPDATE matched nothing
            // and every guide entry re-downloaded the whole xmltv.php.
            db.execSQL("INSERT OR REPLACE INTO epg_meta(playlist_id, epg_built_at) VALUES(?, ?)", arrayOf<Any?>(playlistId, builtAtMs))
            db.execSQL("UPDATE ingest_meta SET epg_built_at = ? WHERE playlist_id = ?", arrayOf<Any?>(builtAtMs, playlistId))
        }
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
                // Into the shadow: replaceEpg swaps it into the live table only once the fill completes.
                val s = db.compileStatement("INSERT INTO $EPG_SHADOW(playlist_id, channel_id, start_ms, end_ms, title, desc, has_archive) VALUES(?,?,?,?,?,?,?)")
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
        /** Catalog tables whose rows carry their source position (`ord`, B64 follow-up). */
        private val ORDERED_TABLES = listOf("channels", "vod", "series")

        const val TYPE_LIVE = "live"
        const val TYPE_VOD = "vod"
        const val TYPE_SERIES = "series"
        /** Insert batch size — matches XtreamMatchIndex's chunk to keep write locks short. */
        const val CHUNK = 5_000

        /** Staging table for [replaceEpg]'s generation swap; holds only the in-flight refresh's rows. */
        private const val EPG_SHADOW = "epg_programmes_shadow"
        /** Per-playlist guide freshness, for every source type (see [replaceEpg]). */
        private const val EPG_META_DDL =
            "CREATE TABLE IF NOT EXISTS epg_meta(playlist_id TEXT NOT NULL PRIMARY KEY, epg_built_at INTEGER NOT NULL) WITHOUT ROWID"
        /** B64: the item-id scheme each playlist's catalog was built with (absent = 1, pre-B64). */
        private const val ID_SCHEME_DDL =
            "CREATE TABLE IF NOT EXISTS id_scheme(playlist_id TEXT NOT NULL PRIMARY KEY, scheme INTEGER NOT NULL) WITHOUT ROWID"
        /** B64: old (pre-B64) id suffix -> new, per playlist, captured from the last pre-B64 catalog. */
        private const val LEGACY_IDS_DDL =
            "CREATE TABLE IF NOT EXISTS m3u_legacy_ids(playlist_id TEXT NOT NULL, old_id TEXT NOT NULL, new_id TEXT NOT NULL, " +
                "series_id TEXT, season INTEGER, episode INTEGER, series_name TEXT, PRIMARY KEY(playlist_id, old_id)) WITHOUT ROWID"
        private const val EPG_SHADOW_DDL =
            "CREATE TABLE IF NOT EXISTS $EPG_SHADOW(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, " +
                "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, desc TEXT, " +
                "has_archive INTEGER NOT NULL DEFAULT 0)"
    }
}

private fun android.database.Cursor.getStringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
