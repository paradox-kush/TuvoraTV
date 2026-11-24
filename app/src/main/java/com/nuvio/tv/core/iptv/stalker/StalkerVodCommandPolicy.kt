package com.nuvio.tv.core.iptv.stalker

/**
 * B02: what `create_link type=vod` is sent, decided from the catalogue row's ids and flags.
 *
 * Stock Ministra (vod.class.php) accepts exactly two cmd shapes: `/media/<id>.mpg` and
 * `/media/file_<id>.mpg`. A row with a custom URL (`rtsp_url`, or a file row with
 * `protocol=custom`) carries that URL as its `cmd`; a real MAG box (c/player.js) rebuilds the
 * `/media/` form from the id instead of echoing the URL. Only PROOF of such a row (`is_file`, a
 * non-blank `rtsp_url`, `has_files > 0`) rewrites the cmd; a raw URL without proof may be an
 * XC-emulator cmd that create_link accepts as-is, so it is sent unchanged first and falls back
 * (file rows, then `/media/<id>.mpg`) only if the portal gives no link. Pure: no I/O.
 */
object StalkerVodCommandPolicy {

    /** The movie row fields the decision reads. [hasFiles] null = the row did not say (a cold stored row keeps only cmd). */
    data class Row(
        val id: Int?,
        val cmd: String?,
        val rtspUrl: String? = null,
        val hasFiles: Int? = null,
        val isFile: Boolean = false,
    )

    sealed interface Decision {
        /**
         * Send exactly this cmd. [fallback] (the id form) is set only for an unproven raw URL: if create_link gives
         * no link the file rows are tried, then [fallback].
         */
        data class Cmd(val cmd: String, val fallback: String? = null) : Decision

        /** Ask the portal for the file rows first, then mint `/media/file_<id>.mpg`. [fallback] is tried only if that finds no file. */
        data class ResolveFiles(val fallback: String?) : Decision
    }

    fun decide(row: Row): Decision {
        val cmd = row.cmd?.trim().orEmpty()
        val id = row.id
        if (row.isFile && id != null) return Decision.Cmd(fileCmd(id))
        if ((row.hasFiles ?: 0) > 0) {
            // Real MAG boxes go to the file row first (c/vclub.js): only a file-form cmd is already a file id.
            return if (isFileCmd(cmd)) Decision.Cmd(cmd) else Decision.ResolveFiles(fallback = null)
        }
        if (id != null && !row.rtspUrl.isNullOrBlank()) return Decision.Cmd(movieCmd(id))
        if (cmd.isBlank()) return Decision.ResolveFiles(fallback = id?.let(::movieCmd))
        // No proof (no rtsp_url, has_files 0 or unknown, cold row): may be an XC-emulator cmd. Unchanged first.
        if (isRawUrl(cmd)) return Decision.Cmd(cmd, fallback = id?.let(::movieCmd))
        return Decision.Cmd(cmd)   // /media/<id>.mpg, XC base64 / opaque cmd: unchanged
    }

    /** The create_link cmd for a file row of get_ordered_list (movie, or Ministra episode files). */
    fun fileRowCmd(fileId: Int?, cmd: String?): String? {
        val c = cmd?.trim().orEmpty()
        if (isMediaCmd(c)) return c
        if (fileId != null) return fileCmd(fileId)
        return c.takeIf { it.isNotBlank() && !isRawUrl(it) }
    }

    fun movieCmd(id: Int) = "/media/$id.mpg"
    fun fileCmd(fileId: Int) = "/media/file_$fileId.mpg"

    private fun isFileCmd(cmd: String): Boolean = FILE_CMD.containsMatchIn(cmd)

    fun isMediaCmd(cmd: String): Boolean = MEDIA_CMD.containsMatchIn(cmd.trim())

    /** An http(s)/rtsp/rtp/udp URL, launcher prefix tolerated: never create_link input. */
    fun isRawUrl(cmd: String): Boolean =
        StalkerProtocol.stripLauncherPrefix(cmd).let { RAW_URL.containsMatchIn(it) }

    /**
     * The playable URL out of a create_link reply cmd: launcher prefix stripped, a relative reply
     * (`/media/...`, `?token=...`) resolved against the portal, never a non-http result.
     * [portalOrigin] is scheme+host[:port]; [requestedCmd] anchors a bare `?query` reply.
     */
    fun resolveReply(reply: String?, portalOrigin: String, requestedCmd: String): String? {
        if (reply.isNullOrBlank()) return null
        StalkerProtocol.extractStreamUrl(reply)?.let { return it }
        val rel = StalkerProtocol.stripLauncherPrefix(reply).split(Regex("\\s+")).firstOrNull().orEmpty()
        val origin = portalOrigin.trimEnd('/')
        if (origin.isEmpty()) return null
        return when {
            rel.startsWith("/") && !rel.startsWith("//") -> origin + rel
            rel.startsWith("?") ->
                origin + (requestedCmd.trim().takeIf { it.startsWith("/") }?.substringBefore('?') ?: "/") + rel
            else -> null
        }
    }

    private val MEDIA_CMD = Regex("""^(?:(?:ffmpeg|auto|ffrt\d*)\s+)?/media/(?:file_)?\d+\.mpg""", RegexOption.IGNORE_CASE)
    private val FILE_CMD = Regex("""/media/file_\d+""")
    private val RAW_URL = Regex("""^(?:https?|rtsp|rtp|udp)://""", RegexOption.IGNORE_CASE)
}
