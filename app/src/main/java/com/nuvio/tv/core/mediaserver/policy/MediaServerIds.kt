package com.nuvio.tv.core.mediaserver.policy

import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerType

/**
 * Identity conventions of media-server content (design 4), fixed once so everything keys on them:
 *
 *  - content id      `ms:{type}:{machineId}:{userId}:{kind}:{itemId}` (kind in movie/series/season/episode);
 *    parsed from the RIGHT - the last two colon fields are kind + item id; the segments before `ms:` are
 *    colon-free by the key rules (a server id / user id never carries `:`);
 *  - direct provider id `ms`; matched provider id `ms-match:{serverKey}`;
 *  - deferred URL    `ms-deferred:{serverKey}|{itemId}|{mediaSourceId}` - minted at pick time only, so the
 *    stream a list shows never holds a token and a stale link can never be played;
 *  - telemetry id    `ms:{type}:{hash(serverKey + installSalt)}:{kind}:{itemId}` - the machine id never leaves the device.
 */
internal object MediaServerIds {
    const val CONTENT_PREFIX = "ms"
    /** The stream group of a server's own items (direct lane). */
    const val DIRECT_GROUP_ID = "ms"
    const val MATCH_GROUP_PREFIX = "ms-match:"
    const val DEFERRED_PREFIX = "ms-deferred:"

    enum class Kind(val slug: String) {
        MOVIE("movie"), SERIES("series"), SEASON("season"), EPISODE("episode");

        companion object {
            fun fromSlug(slug: String?): Kind? = entries.firstOrNull { it.slug == slug }
        }
    }

    data class Parsed(val type: MediaServerType, val machineId: String, val userId: String, val kind: Kind, val itemId: String) {
        val serverKey: String get() = "${type.wire}:$machineId:$userId"
        val sourceKey: String get() = "${type.wire}:$machineId"
    }

    fun contentId(entry: MediaServerEntry, kind: Kind, itemId: String): String = contentId(entry.serverKey, kind, itemId)

    fun contentId(serverKey: String, kind: Kind, itemId: String): String = "$CONTENT_PREFIX:$serverKey:${kind.slug}:$itemId"

    fun isContentId(id: String?): Boolean = id != null && id.startsWith("$CONTENT_PREFIX:")

    /** The `ms:` id split into its parts, or null when it is not a well-formed media-server id. */
    fun parse(id: String): Parsed? {
        if (!isContentId(id)) return null
        val parts = id.split(':')
        // ms : type : machine : user : kind : item   (item ids are hex/numeric; tolerate extra colons by re-joining)
        if (parts.size < 6) return null
        val type = MediaServerType.fromWire(parts[1]) ?: return null
        val machine = parts[2]
        val user = parts[3]
        val kind = Kind.fromSlug(parts[4]) ?: return null
        val item = parts.drop(5).joinToString(":")
        if (machine.isBlank() || user.isBlank() || item.isBlank()) return null
        return Parsed(type, machine, user, kind, item)
    }

    /** `{type}:{machineId}:{userId}` -> its parts, or null. */
    fun parseServerKey(serverKey: String): Triple<MediaServerType, String, String>? {
        val parts = serverKey.split(':')
        if (parts.size != 3 || parts.any { it.isBlank() }) return null
        val type = MediaServerType.fromWire(parts[0]) ?: return null
        return Triple(type, parts[1], parts[2])
    }

    fun isOwnContentId(id: String): Boolean = isContentId(id)

    fun isOwnProviderId(addonId: String): Boolean = addonId == DIRECT_GROUP_ID || addonId.startsWith(MATCH_GROUP_PREFIX)

    fun matchGroupId(serverKey: String): String = "$MATCH_GROUP_PREFIX$serverKey"

    fun serverKeyOfMatchGroup(groupId: String): String? =
        groupId.takeIf { it.startsWith(MATCH_GROUP_PREFIX) }?.removePrefix(MATCH_GROUP_PREFIX)?.takeIf { it.isNotBlank() }

    fun deferredUrl(serverKey: String, itemId: String, mediaSourceId: String?): String =
        "$DEFERRED_PREFIX$serverKey|$itemId|${mediaSourceId.orEmpty()}"

    fun isDeferredUrl(url: String?): Boolean = url != null && url.startsWith(DEFERRED_PREFIX)

    data class Deferred(val serverKey: String, val itemId: String, val mediaSourceId: String?)

    fun parseDeferred(url: String): Deferred? {
        if (!isDeferredUrl(url)) return null
        val parts = url.removePrefix(DEFERRED_PREFIX).split('|')
        if (parts.size != 3 || parts[0].isBlank() || parts[1].isBlank()) return null
        return Deferred(parts[0], parts[1], parts[2].takeIf { it.isNotBlank() })
    }

    /** The id an analytics event may carry for [parsed]: a salted hash instead of the machine id (never the user id). */
    fun telemetryId(parsed: Parsed, installSalt: String): String =
        "$CONTENT_PREFIX:${parsed.type.wire}:${hash("${parsed.serverKey}|$installSalt")}:${parsed.kind.slug}:${parsed.itemId}"

    /** FNV-1a 64 as 16 hex chars: stable, platform-identical, one-way enough to anonymise (not a security primitive). */
    internal fun hash(text: String): String {
        var h = -0x340d631b7bdddcdbL
        for (b in text.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h.toULong().toString(16).padStart(16, '0')
    }
}
