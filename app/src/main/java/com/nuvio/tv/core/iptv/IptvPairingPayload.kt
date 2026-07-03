package com.nuvio.tv.core.iptv

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * P5 IPTV pairing: turning a submitted pairing payload (a `sync_push_iptv_playlists`-shaped jsonb
 * row typed on the phone/web form) into a persistable [XtreamAccount], plus the tiny crypto helpers
 * the TV uses to create/claim a pairing session.
 *
 * The whole file is pure (no Android/Hilt deps) so the mapping + code/format + hashing shape are
 * unit-testable without a device or the network.
 *
 * Mapping contract (mirrors [SupabaseIptvPlaylist] / XtreamAccountSyncService's push JSON):
 *  - `xtream`  -> base_url + username + password  (the browsable case).
 *  - `url`     -> the playlist URL (from `url` OR `base_url`) becomes an M3U-URL account.
 *  - `stalker` -> portal_url + mac_address stashed on the account so the row SURVIVES and syncs,
 *                 even though this branch can't browse a Stalker portal yet (spec §4: still SAVE it).
 *  - anything else with a non-empty source_type -> a best-effort row is kept so nothing is lost.
 * Shared options (name, enabled, epg_url, dns_provider, auto_refresh_hours, content_types,
 * category_selections) are applied on top when present.
 */

/** The 6-char pairing code alphabet + format the backend enforces (`^[A-Z0-9]{6}$`). */
private const val PAIRING_CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private const val PAIRING_CODE_LENGTH = 6
val PAIRING_CODE_REGEX = Regex("^[A-Z0-9]{6}$")

/**
 * A random 6-char A-Z0-9 pairing code shown on the TV (and encoded into the QR's `?code=`).
 * Uses [SecureRandom] so codes aren't guessable/sequential.
 */
fun generatePairingCode(random: SecureRandom = SecureRandom()): String =
    buildString(PAIRING_CODE_LENGTH) {
        repeat(PAIRING_CODE_LENGTH) {
            append(PAIRING_CODE_ALPHABET[random.nextInt(PAIRING_CODE_ALPHABET.length)])
        }
    }

/**
 * A random device secret for a pairing session. This NEVER leaves the TV; only its [sha256Hex]
 * is sent to the backend (which hashes it again before storing/comparing). URL-safe base64 of 24
 * random bytes — the exact same generation the tv-login deviceNonce uses.
 */
fun generatePairingSecret(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(24)
    random.nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Lowercase sha256 hex of [value] — what the TV sends as `p_code_hash` (the secret's hash). */
fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Builds the `?code=CODE` pairing URL from the web-form base and a code, tolerating a base with or
 * without a trailing slash and with or without an existing query string.
 */
fun pairingWebUrl(baseUrl: String, code: String): String {
    val trimmed = baseUrl.trim()
    val separator = if (trimmed.contains('?')) "&" else "?"
    return "$trimmed${separator}code=$code"
}

/**
 * Maps a submitted pairing payload to an [XtreamAccount] to persist, or null when the payload is
 * unusable (not an object, or a blank/missing source_type — the backend rejects those on submit,
 * but the client stays defensive). Every recognised source type produces a SAVABLE row; an
 * unbrowsable type (stalker, or a future type) is still persisted so it works once that lands.
 */
fun pairingPayloadToXtreamAccount(payload: JsonElement?): XtreamAccount? {
    val obj = payload as? JsonObject ?: return null
    val sourceType = obj.stringField("source_type")?.takeIf { it.isNotBlank() } ?: return null

    val name = obj.stringField("name")
    val enabled = obj.boolField("enabled") ?: true

    val base: XtreamAccount = when (sourceType) {
        XtreamAccount.SOURCE_XTREAM -> {
            val baseUrl = obj.stringField("base_url")?.takeIf { it.isNotBlank() } ?: return null
            val username = obj.stringField("username")?.takeIf { it.isNotBlank() } ?: return null
            val password = obj.stringField("password") ?: return null
            XtreamAccount(
                id = "$baseUrl|$username",
                name = name?.ifBlank { null } ?: baseUrl,
                baseUrl = baseUrl,
                username = username,
                password = password,
                sourceType = XtreamAccount.SOURCE_XTREAM
            )
        }

        XtreamAccount.SOURCE_URL -> {
            // The playlist URL may arrive under `url` (web-form convention) or `base_url` (the
            // sync row column). Reuse the same builder the settings form uses so ids match.
            val url = (obj.stringField("url") ?: obj.stringField("base_url"))
                ?.takeIf { it.isNotBlank() } ?: return null
            val userAgent = obj.stringField("user_agent") ?: obj.stringField("username")
            m3uAccountFromUrl(url, userAgent = userAgent?.takeIf { it.isNotBlank() }, name = name?.ifBlank { null })
                ?: return null
        }

        XtreamAccount.SOURCE_STALKER -> {
            // No browse support yet — but keep the row so it syncs + works once Stalker lands.
            // Stash portal_url in baseUrl and mac_address in username (the model has no dedicated
            // fields; these round-trip through the sync JSON columns).
            val portal = (obj.stringField("portal_url") ?: obj.stringField("base_url"))
                ?.takeIf { it.isNotBlank() } ?: return null
            val mac = obj.stringField("mac_address").orEmpty()
            XtreamAccount(
                id = "stalker:$portal|$mac",
                name = name?.ifBlank { null } ?: portal,
                baseUrl = portal,
                username = mac,
                password = obj.stringField("password").orEmpty(),
                sourceType = XtreamAccount.SOURCE_STALKER
            )
        }

        else -> {
            // Unknown but non-blank source type from a newer form: persist a best-effort row so the
            // playlist isn't silently dropped (spec §4). base_url/username/password if present.
            XtreamAccount(
                id = "${sourceType}:${obj.stringField("base_url").orEmpty()}|${obj.stringField("username").orEmpty()}",
                name = name?.ifBlank { null } ?: sourceType,
                baseUrl = obj.stringField("base_url").orEmpty(),
                username = obj.stringField("username").orEmpty(),
                password = obj.stringField("password").orEmpty(),
                sourceType = sourceType
            )
        }
    }

    return base.copy(
        enabled = enabled,
        epgUrl = obj.stringField("epg_url")?.takeIf { it.isNotBlank() } ?: base.epgUrl,
        dnsProvider = obj.stringField("dns_provider")?.takeIf { it.isNotBlank() } ?: base.dnsProvider,
        autoRefreshHours = obj.intField("auto_refresh_hours") ?: base.autoRefreshHours,
        contentTypes = obj.stringListField("content_types")?.toSet()?.takeIf { it.isNotEmpty() } ?: base.contentTypes,
        categorySelections = pairingCategorySelections(obj["category_selections"]) ?: base.categorySelections
    )
}

/** Lenient jsonb -> CategorySelections (null when absent/garbage so the account default is kept). */
private fun pairingCategorySelections(element: JsonElement?): CategorySelections? {
    val obj = element as? JsonObject ?: return null
    fun list(key: String): List<String>? = (obj[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
    return CategorySelections(live = list("live"), movies = list("movies"), series = list("series"))
}

private fun JsonObject.stringField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.boolField(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.intField(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.stringListField(key: String): List<String>? =
    (this[key] as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
