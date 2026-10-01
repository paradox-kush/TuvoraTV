package com.nuvio.tv.core.iptv

/**
 * Step 0.3 — validation + normalization of a playlist's backup-server list, run when the form saves.
 *
 * Pure string work (no URL library) so the result is byte-identical everywhere: twins are NuvioMobile/
 * NuvioDesktop `features/iptv/BackupServerValidation.kt` and nuvio-web `src/lib/iptv/backupServers.ts`, and all of them
 * pin the same golden cases (`BackupServerGolden`).
 *
 *  | Xtream / Stalker | each entry is a base URL → stored as `scheme://host[:port]` (scheme + host
 *  |                  | lowercased, default port dropped, path/query dropped — the add form's rule)
 *  | M3U link         | each entry is a full playlist URL → stored trimmed, `http://` prepended when it
 *  |                  | has no scheme; path + query kept (it is the fetch target)
 *  | M3U file         | no backups — always an empty list
 *
 * Rules: blank rows are ignored; http(s) only; no duplicate of the main server or of an earlier row
 * (compared normalized: lowercase scheme + host, default port dropped, no trailing slash); at most
 * [MAX_BACKUPS]. Order = priority.
 */
object BackupServerValidation {

    const val MAX_BACKUPS: Int = 5

    enum class Problem { INVALID_URL, NOT_HTTP, DUPLICATE_OF_MAIN, DUPLICATE, TOO_MANY }

    data class RowProblem(val index: Int, val problem: Problem)

    data class Outcome(val urls: List<String>, val problems: List<RowProblem>) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /** Whether [sourceType] can have backup servers at all (the UI hides the section otherwise). */
    fun supportsBackups(sourceType: String): Boolean =
        sourceType == XtreamAccount.SOURCE_XTREAM || sourceType == XtreamAccount.SOURCE_URL || sourceType == XtreamAccount.SOURCE_STALKER

    fun validate(sourceType: String, main: String, entries: List<String>): Outcome {
        if (!supportsBackups(sourceType)) return Outcome(emptyList(), emptyList())
        val mainKey = comparisonKey(sourceType, main)
        val urls = ArrayList<String>()
        val keys = HashSet<String>()
        val problems = ArrayList<RowProblem>()
        entries.forEachIndexed { index, raw ->
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return@forEachIndexed
            if (hasNonHttpScheme(trimmed)) {
                problems += RowProblem(index, Problem.NOT_HTTP)
                return@forEachIndexed
            }
            val stored = normalize(sourceType, trimmed)
            val key = stored?.let { comparisonKey(sourceType, it) }
            when {
                stored == null || key == null -> problems += RowProblem(index, Problem.INVALID_URL)
                key == mainKey -> problems += RowProblem(index, Problem.DUPLICATE_OF_MAIN)
                key in keys -> problems += RowProblem(index, Problem.DUPLICATE)
                urls.size >= MAX_BACKUPS -> problems += RowProblem(index, Problem.TOO_MANY)
                else -> {
                    urls += stored
                    keys += key
                }
            }
        }
        return Outcome(urls, problems)
    }

    /** The stored form of one non-blank http(s) entry, or null when it is not a usable URL. */
    fun normalize(sourceType: String, entry: String): String? {
        val trimmed = entry.trim()
        if (trimmed.isEmpty() || hasNonHttpScheme(trimmed)) return null
        val withScheme = PlaylistKey.withHttpScheme(trimmed)
        val origin = saneOrigin(withScheme) ?: return null
        return if (sourceType == XtreamAccount.SOURCE_URL) withScheme else origin
    }

    /**
     * The duplicate-detection key: lowercase scheme + host, default port dropped, plus — for an M3U
     * link only — the path + query with any trailing slash removed. Null when unusable.
     */
    fun comparisonKey(sourceType: String, url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || hasNonHttpScheme(trimmed)) return null
        val withScheme = PlaylistKey.withHttpScheme(trimmed)
        val origin = saneOrigin(withScheme) ?: return null
        if (sourceType != XtreamAccount.SOURCE_URL) return origin
        return origin + afterAuthority(withScheme).trimEnd('/')
    }

    /** "ftp://…", "rtsp://…": a scheme that is present and is not http(s). */
    private fun hasNonHttpScheme(s: String): Boolean {
        val sep = s.indexOf("://")
        if (sep <= 0) return false
        val scheme = s.substring(0, sep)
        // ASCII-only on purpose: the TS twin uses /^[A-Za-z][A-Za-z0-9+.-]*$/ and the two must agree.
        if (!scheme[0].isAsciiLetter() || !scheme.all { it.isAsciiLetter() || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return false
        val lower = scheme.lowercase()
        return lower != "http" && lower != "https"
    }

    private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

    /** [PlaylistKey.origin] plus the checks a typed URL needs: a host, and no whitespace in it. */
    private fun saneOrigin(withScheme: String): String? {
        val origin = PlaylistKey.origin(withScheme) ?: return null
        val host = origin.substringAfter("://")
        if (host.isEmpty() || host.startsWith(":") || host.any { it.isWhitespace() }) return null
        return origin
    }

    /** Everything after `scheme://authority` (path, query, fragment), or "". */
    private fun afterAuthority(withScheme: String): String {
        val rest = withScheme.substring(withScheme.indexOf("://") + 3)
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        return if (end < 0) "" else rest.substring(end)
    }
}

/**
 * Step 0.3 — the backup-server editor's row operations (add / remove / move up-down / edit), pure so
 * the form composable only renders and forwards. Rows are the raw typed strings; validation runs on save.
 */
object BackupServerListEdits {

    /** Whether another (blank) row may be added. */
    fun canAdd(rows: List<String>): Boolean = rows.size < BackupServerValidation.MAX_BACKUPS

    fun add(rows: List<String>): List<String> = if (canAdd(rows)) rows + "" else rows

    fun remove(rows: List<String>, index: Int): List<String> =
        if (index in rows.indices) rows.filterIndexed { i, _ -> i != index } else rows

    fun update(rows: List<String>, index: Int, value: String): List<String> =
        if (index in rows.indices) rows.mapIndexed { i, v -> if (i == index) value else v } else rows

    fun moveUp(rows: List<String>, index: Int): List<String> = swap(rows, index, index - 1)

    fun moveDown(rows: List<String>, index: Int): List<String> = swap(rows, index, index + 1)

    /** An action the per-row editor sheet offers. Remove is destructive, so it confirms first (UX100). */
    enum class RowAction(val needsConfirmation: Boolean) { MOVE_UP(false), MOVE_DOWN(false), REMOVE(true) }

    /**
     * The actions the editor sheet shows for row [index] of [count], top to bottom (UX100). A move
     * that can't act is left out rather than shown disabled: D-pad focus skips a disabled row, which
     * once carried DOWN-DOWN from the address straight onto Remove.
     */
    fun rowActions(index: Int, count: Int): List<RowAction> = buildList {
        if (index > 0) add(RowAction.MOVE_UP)
        if (index < count - 1) add(RowAction.MOVE_DOWN)
        add(RowAction.REMOVE)
    }

    private fun swap(rows: List<String>, a: Int, b: Int): List<String> {
        if (a !in rows.indices || b !in rows.indices) return rows
        val out = rows.toMutableList()
        out[a] = rows[b]
        out[b] = rows[a]
        return out
    }

}
