package com.nuvio.tv.core.iptv.overlay

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk store for the IPTV personalization overlay (hide / pin / reorder / custom groups), keyed on
 * the canon-v1 identity so it matches the ids the website + phone/desktop write. Profile-scoped, sparse.
 * TV twin of NuvioMobile's IptvOverlayStore — framework SQLiteOpenHelper (the IptvContentDb pattern),
 * not androidx.sqlite. updated_at + deleted are the delta-sync affordance; reads ignore deleted=1.
 */
@Singleton
class IptvOverlayDb @Inject constructor(@ApplicationContext context: Context) {

    private val helper = object : SQLiteOpenHelper(context, "iptv_overlay.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE channel_overlay(profile_id INTEGER NOT NULL, entity_id TEXT NOT NULL, playlist_id TEXT, " +
                    "hidden INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, position INTEGER, rename TEXT, " +
                    "updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(profile_id, entity_id)) WITHOUT ROWID",
            )
            db.execSQL("CREATE INDEX channel_overlay_pl ON channel_overlay(profile_id, playlist_id)")
            db.execSQL(
                "CREATE TABLE category_overlay(profile_id INTEGER NOT NULL, playlist_id TEXT NOT NULL, content_type TEXT NOT NULL, " +
                    "category_key TEXT NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, pinned INTEGER NOT NULL DEFAULT 0, position INTEGER, " +
                    "rename TEXT, updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(profile_id, playlist_id, content_type, category_key)) WITHOUT ROWID",
            )
            db.execSQL(
                "CREATE TABLE custom_group(profile_id INTEGER NOT NULL, group_id TEXT NOT NULL, playlist_id TEXT, content_type TEXT NOT NULL, " +
                    "name TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(profile_id, group_id)) WITHOUT ROWID",
            )
            db.execSQL(
                "CREATE TABLE custom_group_member(profile_id INTEGER NOT NULL, group_id TEXT NOT NULL, entity_id TEXT NOT NULL, " +
                    "position INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(profile_id, group_id, entity_id)) WITHOUT ROWID",
            )
            db.execSQL("CREATE TABLE overlay_cursor(profile_id INTEGER NOT NULL PRIMARY KEY, cursor INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            for (t in listOf("channel_overlay", "category_overlay", "custom_group", "custom_group_member", "overlay_cursor")) {
                db.execSQL("DROP TABLE IF EXISTS $t")
            }
            onCreate(db)
        }
    }

    private val db get() = helper.writableDatabase

    @Synchronized
    fun snapshot(profileId: Int): OverlaySnapshot {
        val channels = HashMap<String, ChannelOverlay>()
        db.rawQuery("SELECT entity_id, hidden, pinned, position, rename FROM channel_overlay WHERE profile_id=? AND deleted=0", arrayOf(profileId.toString())).use { c ->
            while (c.moveToNext()) channels[c.getString(0)] = ChannelOverlay(c.getInt(1) != 0, c.getInt(2) != 0, if (c.isNull(3)) null else c.getInt(3), if (c.isNull(4)) null else c.getString(4))
        }
        val categories = HashMap<String, CategoryOverlay>()
        db.rawQuery("SELECT category_key, hidden, pinned, position, rename FROM category_overlay WHERE profile_id=? AND deleted=0", arrayOf(profileId.toString())).use { c ->
            while (c.moveToNext()) categories[c.getString(0)] = CategoryOverlay(c.getInt(1) != 0, c.getInt(2) != 0, if (c.isNull(3)) null else c.getInt(3), if (c.isNull(4)) null else c.getString(4))
        }
        val members = HashMap<String, MutableList<Pair<Int, String>>>()
        db.rawQuery("SELECT group_id, entity_id, position FROM custom_group_member WHERE profile_id=? AND deleted=0", arrayOf(profileId.toString())).use { c ->
            while (c.moveToNext()) members.getOrPut(c.getString(0)) { mutableListOf() }.add(c.getInt(2) to c.getString(1))
        }
        val groups = ArrayList<CustomGroup>()
        db.rawQuery("SELECT group_id, playlist_id, content_type, name, position FROM custom_group WHERE profile_id=? AND deleted=0", arrayOf(profileId.toString())).use { c ->
            while (c.moveToNext()) {
                val gid = c.getString(0)
                groups.add(CustomGroup(gid, c.getString(2), if (c.isNull(1)) null else c.getString(1), c.getString(3), c.getInt(4), members[gid]?.sortedBy { it.first }?.map { it.second } ?: emptyList()))
            }
        }
        return OverlaySnapshot(channels, categories, groups)
    }

    @Synchronized
    fun setChannel(profileId: Int, entityId: String, playlistId: String?, o: ChannelOverlay, updatedAt: Long, deletedOverride: Boolean? = null) {
        val deleted = deletedOverride ?: o.isNoop
        db.execSQL(
            "INSERT INTO channel_overlay(profile_id, entity_id, playlist_id, hidden, pinned, position, rename, updated_at, deleted) VALUES(?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(profile_id, entity_id) DO UPDATE SET playlist_id=excluded.playlist_id, hidden=excluded.hidden, pinned=excluded.pinned, " +
                "position=excluded.position, rename=excluded.rename, updated_at=excluded.updated_at, deleted=excluded.deleted",
            arrayOf<Any?>(profileId, entityId, playlistId, if (o.hidden) 1 else 0, if (o.pinned) 1 else 0, o.position, o.rename, updatedAt, if (deleted) 1 else 0),
        )
    }

    @Synchronized
    fun setCategory(profileId: Int, playlistId: String, contentType: String, categoryKey: String, o: CategoryOverlay, updatedAt: Long, deletedOverride: Boolean? = null) {
        val deleted = deletedOverride ?: o.isNoop
        db.execSQL(
            "INSERT INTO category_overlay(profile_id, playlist_id, content_type, category_key, hidden, pinned, position, rename, updated_at, deleted) VALUES(?,?,?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(profile_id, playlist_id, content_type, category_key) DO UPDATE SET hidden=excluded.hidden, pinned=excluded.pinned, position=excluded.position, " +
                "rename=excluded.rename, updated_at=excluded.updated_at, deleted=excluded.deleted",
            arrayOf<Any?>(profileId, playlistId, contentType, categoryKey, if (o.hidden) 1 else 0, if (o.pinned) 1 else 0, o.position, o.rename, updatedAt, if (deleted) 1 else 0),
        )
    }

    @Synchronized fun getCursor(profileId: Int): Long =
        db.rawQuery("SELECT cursor FROM overlay_cursor WHERE profile_id=?", arrayOf(profileId.toString())).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    @Synchronized fun setCursor(profileId: Int, cursor: Long) =
        db.execSQL("INSERT INTO overlay_cursor(profile_id, cursor) VALUES(?,?) ON CONFLICT(profile_id) DO UPDATE SET cursor=excluded.cursor", arrayOf<Any?>(profileId, cursor))

    @Synchronized
    fun applyRemoteGroup(profileId: Int, groupId: String, playlistId: String?, contentType: String, name: String, position: Int, updatedAt: Long, deleted: Boolean) =
        db.execSQL(
            "INSERT INTO custom_group(profile_id, group_id, playlist_id, content_type, name, position, updated_at, deleted) VALUES(?,?,?,?,?,?,?,?) " +
                "ON CONFLICT(profile_id, group_id) DO UPDATE SET playlist_id=excluded.playlist_id, content_type=excluded.content_type, name=excluded.name, position=excluded.position, updated_at=excluded.updated_at, deleted=excluded.deleted",
            arrayOf<Any?>(profileId, groupId, playlistId, contentType, name, position, updatedAt, if (deleted) 1 else 0),
        )

    @Synchronized
    fun applyRemoteMember(profileId: Int, groupId: String, entityId: String, position: Int, updatedAt: Long, deleted: Boolean) =
        db.execSQL(
            "INSERT INTO custom_group_member(profile_id, group_id, entity_id, position, updated_at, deleted) VALUES(?,?,?,?,?,?) " +
                "ON CONFLICT(profile_id, group_id, entity_id) DO UPDATE SET position=excluded.position, updated_at=excluded.updated_at, deleted=excluded.deleted",
            arrayOf<Any?>(profileId, groupId, entityId, position, updatedAt, if (deleted) 1 else 0),
        )

    /** Rows (channel edits) to push to the server, as (kind, okey, playlistId, valueJson, updatedAt, deleted). */
    @Synchronized
    fun channelRowsForPush(profileId: Int): List<OverlayPushRow> {
        val out = ArrayList<OverlayPushRow>()
        db.rawQuery("SELECT entity_id, playlist_id, hidden, pinned, position, rename, updated_at, deleted FROM channel_overlay WHERE profile_id=?", arrayOf(profileId.toString())).use { c ->
            while (c.moveToNext()) {
                val v = buildString {
                    append("{\"hidden\":").append(c.getInt(2) != 0).append(",\"pinned\":").append(c.getInt(3) != 0)
                    if (!c.isNull(4)) append(",\"position\":").append(c.getInt(4))
                    if (!c.isNull(5)) append(",\"rename\":\"").append(c.getString(5).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"")
                    append("}")
                }
                out.add(OverlayPushRow("channel", c.getString(0), if (c.isNull(1)) null else c.getString(1), v, c.getLong(6), c.getInt(7) != 0))
            }
        }
        return out
    }
}
