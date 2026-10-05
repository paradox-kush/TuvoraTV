package com.nuvio.tv.core.iptv

/**
 * Step 0 — the permanent playlist id ("playlist key") for a NEW playlist.
 *
 * A playlist's id keys everything the user makes against it: library / watch progress / watched
 * (`xtream:{id}:…`), live favourites and recents, and the IPTV overlay (whose hidden/pinned channel
 * keys are hashes that INCLUDE the id, so they can never be re-keyed). Ids used to be re-derived from
 * the address on every edit and every pull, so a provider moving domains orphaned all of it. The id is
 * now minted ONCE, here, when a playlist is added; the server stores it (`iptv_playlists.playlist_key`)
 * and every device adopts it on pull. An address edit never changes it again.
 *
 * Pure string work (no URL library) so the output is byte-identical on every platform: this file's
 * twins are NuvioMobile/NuvioDesktop `features/iptv/PlaylistKey.kt` and nuvio-web
 * `src/lib/iptv/playlistKey.ts`, and all three pin the same golden vectors.
 *
 *  | Xtream   | `scheme://host[:port]|username` — scheme + host lowercased, the scheme's default port
 *  |          | (http 80 / https 443) dropped, path/query dropped, username as entered (trimmed)
 *  | M3U link | `m3u|` + URL trimmed, `http://` prepended when it has no http(s) scheme, with the LOGIN
 *  |          | removed ([com.nuvio.tv.core.iptv.identity.M3uIdentity.playlistKey], B64): user-info and
 *  |          | username/password query params dropped, `|u<hex8>` appended when a username was present.
 *  |          | A URL without a login keeps its pre-B64 key byte-for-byte.
 *  | Stalker  | `stalker|` + `scheme://host[:port]` (as Xtream) + `|` + MAC trimmed and uppercased
 *  | M3U file | `m3u_file|` + file name (trimmed) + `|` + the creation epoch ms
 *
 * Every builder returns null when its required parts are blank / unparseable.
 */
object PlaylistKey {

    fun xtream(serverUrl: String, username: String): String? {
        val origin = origin(serverUrl) ?: return null
        val user = username.trim().takeIf { it.isNotEmpty() } ?: return null
        return "$origin|$user"
    }

    fun m3uUrl(url: String): String? =
        com.nuvio.tv.core.iptv.identity.M3uIdentity.playlistKey(url)

    fun stalker(portalUrl: String, macAddress: String): String? {
        val origin = origin(portalUrl) ?: return null
        val mac = macAddress.trim().uppercase().takeIf { it.isNotEmpty() } ?: return null
        return "stalker|$origin|$mac"
    }

    fun m3uFile(fileName: String, creationEpochMs: Long): String? {
        val name = fileName.trim().takeIf { it.isNotEmpty() } ?: return null
        return "m3u_file|$name|$creationEpochMs"
    }

    /** [raw] with `http://` prepended unless it already starts with `http://` / `https://` (any case). */
    fun withHttpScheme(raw: String): String =
        if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw
        else "http://$raw"

    /**
     * `scheme://host[:port]` of [input]: scheme + host lowercased, user-info / path / query / fragment
     * dropped, the scheme's default port dropped. Null when there is no host or the port is not a
     * number in 1..65535.
     */
    fun origin(input: String): String? {
        val raw = input.trim().takeIf { it.isNotEmpty() } ?: return null
        val full = withHttpScheme(raw)
        val sep = full.indexOf("://")
        val scheme = full.substring(0, sep).lowercase()
        val rest = full.substring(sep + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end).let { a -> a.substring(a.lastIndexOf('@') + 1) }
        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            host = authority.substring(0, close + 1)
            val tail = authority.substring(close + 1)
            if (tail.isNotEmpty() && !tail.startsWith(":")) return null
            portText = tail.removePrefix(":")
        } else {
            val colon = authority.lastIndexOf(':')
            host = if (colon < 0) authority else authority.substring(0, colon)
            portText = if (colon < 0) "" else authority.substring(colon + 1)
        }
        if (host.isEmpty() || host == "[]") return null
        val port: Int? = if (portText.isEmpty()) null else {
            if (!portText.all { it in '0'..'9' } || portText.length > 5) return null
            portText.toInt().takeIf { it in 1..65535 } ?: return null
        }
        val defaultPort = when (scheme) { "http" -> 80; "https" -> 443; else -> -1 }
        val portSuffix = if (port == null || port == defaultPort) "" else ":$port"
        return "$scheme://${host.lowercase()}$portSuffix"
    }

    /**
     * Whether [a] and [b] point at the same provider ADDRESS (not the same id): the identity a pulled
     * row is matched to a local playlist by when their ids differ. Same source type, and — Xtream: same
     * normalized server + username; M3U link: same URL; Stalker: same normalized portal + MAC;
     * M3U file: same file name.
     */
    fun sameAddress(a: XtreamAccount, b: XtreamAccount): Boolean {
        if (a.sourceType != b.sourceType) return false
        return when (a.sourceType) {
            XtreamAccount.SOURCE_URL -> m3uUrl(a.baseUrl).let { it != null && it == m3uUrl(b.baseUrl) }
            XtreamAccount.SOURCE_FILE -> a.fileName != null && a.fileName == b.fileName
            XtreamAccount.SOURCE_STALKER ->
                stalker(a.stalkerPortal(), a.macAddress).let { it != null && it == stalker(b.stalkerPortal(), b.macAddress) }
            else -> xtream(a.baseUrl, a.username).let { it != null && it == xtream(b.baseUrl, b.username) }
        }
    }

    private fun XtreamAccount.stalkerPortal(): String = portalUrl.ifBlank { baseUrl }
}

/** A pulled playlist and whether its id is the server's stored `playlist_key` (vs a local derivation
 *  from the row's address, used while the server has no key column yet). */
data class PulledPlaylist(val account: XtreamAccount, val serverKeyed: Boolean)

/**
 * Step 0 — the pure decision "what does a pull do to this device's playlist ids".
 *
 * For each pulled row (in order):
 *  1. a local playlist already has its id → nothing to do;
 *  2. otherwise an unclaimed local playlist at the SAME ADDRESS ([PlaylistKey.sameAddress]) is the same
 *     playlist under another id:
 *      - the row carries the server's key → the local id is RE-KEYED to it ([Rekey]: the device moves
 *        its prefix-keyed data from the old id to the key, once). This is the TV `m3u:` id, a rescued
 *        orphan, a file playlist's `|ms` id vs the server's `|synced` key;
 *      - the row has no key (an un-migrated server) → the row takes the LOCAL id (no data moves). Its
 *        derived id is an address, and the local id is frozen across address edits, so adopting the
 *        derivation would re-introduce exactly the bug this step fixes;
 *  3. no match → the row as pulled.
 *
 * Matching is one-to-one. Feeding the result back in yields no rekeys (idempotent).
 */
object PlaylistKeyAdoption {
    data class Rekey(val oldId: String, val newId: String)
    data class Result(val accounts: List<XtreamAccount>, val rekeys: List<Rekey>)

    fun resolve(pulled: List<PulledPlaylist>, local: List<XtreamAccount>): Result {
        val pulledIds = pulled.map { it.account.id }.toSet()
        // A local playlist whose id the pull still lists is that row's — never re-key it to another.
        val claimed = local.filter { it.id in pulledIds }.map { it.id }.toMutableSet()
        val rekeys = mutableListOf<Rekey>()
        val accounts = pulled.map { row ->
            val acc = row.account
            if (local.any { it.id == acc.id }) return@map acc
            val match = local.firstOrNull { it.id !in claimed && PlaylistKey.sameAddress(it, acc) }
                ?: return@map acc
            claimed += match.id
            if (row.serverKeyed) {
                rekeys += Rekey(oldId = match.id, newId = acc.id)
                acc
            } else {
                acc.copy(id = match.id)
            }
        }
        return Result(accounts, rekeys)
    }

    /** [rewritePending] for the in-memory ops a sync is replaying. */
    internal fun rewriteOps(ops: List<PendingPlaylistOp>, rekeys: List<Rekey>): List<PendingPlaylistOp> {
        if (rekeys.isEmpty()) return ops
        val map = rekeys.associate { it.oldId to it.newId }
        fun id(v: String) = map[v] ?: v
        // Gson-decoded rows can hold null in non-null fields (pre-field logs); copy() would throw.
        fun acc(a: XtreamAccount) = runCatching { a.copy(id = id(a.id)) }.getOrDefault(a)
        return ops.map { op ->
            when (op) {
                is PendingPlaylistOp.Add -> PendingPlaylistOp.Add(acc(op.account))
                is PendingPlaylistOp.Update -> PendingPlaylistOp.Update(acc(op.account), op.base?.let(::acc))
                is PendingPlaylistOp.Replace -> PendingPlaylistOp.Replace(id(op.oldId), acc(op.account), op.base?.let(::acc))
                is PendingPlaylistOp.Delete -> PendingPlaylistOp.Delete(id(op.id))
            }
        }
    }

    /** Rewrites every id a durable pending op carries (its own, its row's, its base's, a replace's old
     *  id) through [rekeys], so an edit recorded before the re-key still lands on the re-keyed row. */
    internal fun rewritePending(pending: List<PendingOpDto>, rekeys: List<Rekey>): List<PendingOpDto> {
        if (rekeys.isEmpty()) return pending
        val map = rekeys.associate { it.oldId to it.newId }
        fun id(v: String) = map[v] ?: v
        return pending.map { op ->
            op.copy(
                id = id(op.id),
                account = op.account?.let { a -> runCatching { a.copy(id = id(a.id)) }.getOrDefault(a) },
                base = op.base?.let { a -> runCatching { a.copy(id = id(a.id)) }.getOrDefault(a) },
                oldId = op.oldId?.let(::id),
            )
        }
    }
}

/**
 * Step 0 — the account an edit saves: the form's [candidate] (new address / creds / options) placed
 * over [old]'s identity. The id is ALWAYS [old]'s — a domain move, a username or password change, a
 * new M3U link or MAC never re-keys the playlist (that orphaned every hidden channel, favourite and
 * progress row keyed on it). Content toggles + category picks are not on the edit form, so they carry.
 */
fun XtreamAccount.asEditOf(old: XtreamAccount): XtreamAccount = copy(
    id = old.id,
    enabled = old.enabled,
    contentTypes = old.contentTypes,
    categorySelections = old.categorySelections,
    backupUrls = old.backupUrls,
)
