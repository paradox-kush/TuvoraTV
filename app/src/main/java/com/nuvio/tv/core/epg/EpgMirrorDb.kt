package com.nuvio.tv.core.epg

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.nuvio.tv.core.iptv.content.EpgProgramme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** One channel row of the backend's channels-index (a display name of an EPG channel). */
data class EpgIndexRow(val slug: String, val epgId: String, val name: String)

/** A provider channel's persisted mapping onto a canonical EPG id. */
data class EpgMappingRow(val streamId: Int, val epgId: String, val tier: String)

/**
 * Local store for the backend's EPG mirror (see nuvio-backend supabase/functions/epg-sync):
 * the channels index of every mirrored source, the programme window for the feeds we chose
 * to download, and the provider-channel → EPG-id mappings computed by [EpgChannelIndex].
 *
 * Same discipline as [com.nuvio.tv.core.iptv.content.IptvContentDb]: framework SQLite (zero
 * new deps), chunked insert transactions, meta stamped LAST so a crashed sync reads as stale
 * and re-runs. Everything here is a rebuildable cache — upgrades drop + recreate.
 */
@Singleton
class EpgMirrorDb @Inject constructor(@ApplicationContext context: Context) {

    private val helper = object : SQLiteOpenHelper(context, "epg_mirror.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE index_channels(slug TEXT NOT NULL, epg_id TEXT NOT NULL, name TEXT NOT NULL)")
            db.execSQL("CREATE INDEX index_channels_slug ON index_channels(slug)")
            db.execSQL("CREATE TABLE programmes(epg_id TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, title TEXT NOT NULL, desc TEXT)")
            db.execSQL("CREATE INDEX programmes_lookup ON programmes(epg_id, start_ms)")
            db.execSQL("CREATE TABLE mapping(provider_key TEXT NOT NULL, stream_id INTEGER NOT NULL, epg_id TEXT NOT NULL, tier TEXT NOT NULL, updated_ms INTEGER NOT NULL, PRIMARY KEY(provider_key, stream_id)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX mapping_epg ON mapping(epg_id)")
            db.execSQL("CREATE TABLE meta(k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            for (t in listOf("index_channels", "programmes", "mapping", "meta")) {
                db.execSQL("DROP TABLE IF EXISTS $t")
            }
            onCreate(db)
        }
    }

    private val db: SQLiteDatabase by lazy { helper.writableDatabase }

    // --- meta ----------------------------------------------------------------

    suspend fun meta(key: String): String? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    suspend fun setMeta(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        db.execSQL("INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)", arrayOf(key, value))
    }

    // --- channels index -------------------------------------------------------

    /** Full replace of the channels index (small: one row per display name). */
    suspend fun replaceIndex(rows: List<EpgIndexRow>): Unit = withContext(Dispatchers.IO) {
        inTx {
            db.delete("index_channels", null, null)
            val s = db.compileStatement("INSERT INTO index_channels(slug, epg_id, name) VALUES(?,?,?)")
            for (r in rows) {
                s.clearBindings()
                s.bindString(1, r.slug); s.bindString(2, r.epgId.lowercase()); s.bindString(3, r.name)
                s.executeInsert()
            }
            s.close()
        }
    }

    /** Stream every index row (build the transient [EpgChannelIndex] without a big list copy). */
    suspend fun forEachIndexRow(block: (EpgIndexRow) -> Unit): Unit = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT slug, epg_id, name FROM index_channels", null).use { c ->
            while (c.moveToNext()) block(EpgIndexRow(c.getString(0), c.getString(1), c.getString(2)))
        }
    }

    suspend fun indexIsEmpty(): Boolean = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT 1 FROM index_channels LIMIT 1", null).use { !it.moveToFirst() }
    }

    /** Display name of one EPG channel (first index row wins). */
    suspend fun indexNameFor(epgId: String): String? = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT name FROM index_channels WHERE epg_id = ? LIMIT 1", arrayOf(epgId.lowercase())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    // --- programmes -----------------------------------------------------------

    /**
     * Full replace of the mirrored programme window. [fill] streams programmes in (chunked
     * transactions); the caller stamps freshness meta afterwards, so a crash mid-write just
     * reads as a stale sync and re-runs.
     */
    suspend fun replaceProgrammes(fill: suspend (ProgrammeWriter) -> Unit): Int = withContext(Dispatchers.IO) {
        inTx { db.delete("programmes", null, null) }
        val writer = ProgrammeWriter()
        fill(writer)
        writer.flush()
        writer.count
    }

    inner class ProgrammeWriter internal constructor() {
        private val batch = ArrayList<EpgProgramme>(CHUNK)
        var count = 0; private set

        fun add(p: EpgProgramme) {
            batch.add(p); count++
            if (batch.size >= CHUNK) flush()
        }

        internal fun flush() {
            if (batch.isEmpty()) return
            inTx {
                val s = db.compileStatement("INSERT INTO programmes(epg_id, start_ms, end_ms, title, desc) VALUES(?,?,?,?,?)")
                for (p in batch) {
                    s.clearBindings()
                    s.bindString(1, p.channelId); s.bindLong(2, p.startMs); s.bindLong(3, p.endMs)
                    s.bindString(4, p.title)
                    if (p.desc != null) s.bindString(5, p.desc) else s.bindNull(5)
                    s.executeInsert()
                }
                s.close()
            }
            batch.clear()
        }
    }

    /** Now + next for one EPG channel (same contract as IptvContentDb.epgNowNext). */
    suspend fun nowNext(epgId: String, nowMs: Long): List<EpgProgramme> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT epg_id, start_ms, end_ms, title, desc FROM programmes WHERE epg_id = ? AND end_ms > ? ORDER BY start_ms LIMIT 2",
            arrayOf(epgId.lowercase(), nowMs.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(EpgProgramme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), if (c.isNull(4)) null else c.getString(4)))
                }
            }
        }
    }

    /** Programmes overlapping [fromMs, toMs) for one EPG channel, in start order (guide timeline). */
    suspend fun programmesWindow(epgId: String, fromMs: Long, toMs: Long): List<EpgProgramme> = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT epg_id, start_ms, end_ms, title, desc FROM programmes WHERE epg_id = ? AND end_ms > ? AND start_ms < ? ORDER BY start_ms LIMIT 24",
            arrayOf(epgId.lowercase(), fromMs.toString(), toMs.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(EpgProgramme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), if (c.isNull(4)) null else c.getString(4)))
                }
            }
        }
    }

    /**
     * Programmes overlapping [fromMs, toMs) whose title contains ANY of [tokens]
     * (case-insensitive for ASCII — the callers pass normalized lowercase team tokens).
     * Callers score/verify the rows; this is just the SQL-side candidate cut.
     */
    suspend fun searchProgrammes(tokens: List<String>, fromMs: Long, toMs: Long, limit: Int = 800): List<EpgProgramme> =
        withContext(Dispatchers.IO) {
            val safe = tokens.map { it.lowercase().filter { ch -> ch.isLetterOrDigit() || ch == ' ' } }
                .filter { it.length > 2 }.distinct().take(8)
            if (safe.isEmpty()) return@withContext emptyList()
            val likes = safe.joinToString(" OR ") { "title LIKE ?" }
            val args = arrayOf(toMs.toString(), fromMs.toString()) + safe.map { "%$it%" }
            db.rawQuery(
                "SELECT epg_id, start_ms, end_ms, title, desc FROM programmes WHERE start_ms < ? AND end_ms > ? AND ($likes) LIMIT $limit",
                args
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(EpgProgramme(c.getString(0), c.getLong(1), c.getLong(2), c.getString(3), if (c.isNull(4)) null else c.getString(4)))
                    }
                }
            }
        }

    // --- provider mappings ------------------------------------------------------

    /** Full replace of one provider's channel→EPG mappings. */
    suspend fun replaceMapping(providerKey: String, rows: List<EpgMappingRow>): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        inTx {
            db.delete("mapping", "provider_key = ?", arrayOf(providerKey))
            val s = db.compileStatement("INSERT OR REPLACE INTO mapping(provider_key, stream_id, epg_id, tier, updated_ms) VALUES(?,?,?,?,?)")
            for (r in rows) {
                s.clearBindings()
                s.bindString(1, providerKey); s.bindLong(2, r.streamId.toLong())
                s.bindString(3, r.epgId.lowercase()); s.bindString(4, r.tier); s.bindLong(5, now)
                s.executeInsert()
            }
            s.close()
        }
    }

    /** streamId → epgId for one provider (a few MB at 26k channels; callers hold it briefly). */
    suspend fun mappingFor(providerKey: String): Map<Int, String> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT stream_id, epg_id FROM mapping WHERE provider_key = ?", arrayOf(providerKey)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getInt(0), c.getString(1)) }
        }
    }

    suspend fun mappedProviderKeys(): Set<String> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT DISTINCT provider_key FROM mapping", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    /** Every distinct EPG id any provider channel mapped onto (the programme-parse filter). */
    suspend fun mappedEpgIds(): Set<String> = withContext(Dispatchers.IO) {
        db.rawQuery("SELECT DISTINCT epg_id FROM mapping", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    suspend fun purgeProvider(providerKey: String): Unit = withContext(Dispatchers.IO) {
        inTx { db.delete("mapping", "provider_key = ?", arrayOf(providerKey)) }
    }

    private inline fun inTx(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private companion object {
        const val CHUNK = 5_000
    }
}
