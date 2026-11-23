package com.nuvio.tv.core.iptv.stalker

/**
 * B02: what `create_link type=vod` is sent, decided from the catalogue row's ids and flags.
 *
 * Stock Ministra (vod.class.php) accepts exactly two cmd shapes: `/media/<id>.mpg` and
 * `/media/file_<id>.mpg`. A row with a custom URL (`rtsp_url`, or a file row with
 * `protocol=custom`) carries that URL as its `cmd`; a real MAG box (c/player.js) rebuilds the
 * `/media/` form from the id instead of echoing the URL. Sending the URL makes the portal answer
 * `nothing_to_play`. Pure: no I/O.
 */
object StalkerVodCommandPolicy {

    /** The movie row fields the decision reads. [hasFiles] 0 when the row has no such key. */
    data class Row(
        val id: Int?,
        val cmd: String?,
        val rtspUrl: String? = null,
        val hasFiles: Int = 0,
        val isFile: Boolean = false,
    )

    sealed interface Decision {
        /** Send exactly this cmd. */
        data class Cmd(val cmd: String) : Decision

        /** Ask the portal for the file rows first, then mint `/media/file_<id>.mpg`. [fallback] is tried only if that finds no file. */
        data class ResolveFiles(val fallback: String?) : Decision
    }

    fun decide(row: Row): Decision {
        val cmd = row.cmd?.trim().orEmpty()
        val id = row.id
        if (row.isFile && id != null) return Decision.Cmd(fileCmd(id))
        if (isMediaCmd(cmd)) return Decision.Cmd(cmd)
        if (id != null && !row.rtspUrl.isNullOrBlank()) return Decision.Cmd(movieCmd(id))
        if (row.hasFiles > 0) return Decision.ResolveFiles(fallback = null)
        if (isRawUrl(cmd)) {
            // Only a URL survives (a cold stored row keeps cmd alone): the files may carry the real
            // id, so look there, and keep the movie id form as the last resort.
            return Decision.ResolveFiles(fallback = id?.let(::movieCmd))
        }
        if (cmd.isBlank()) return Decision.ResolveFiles(fallback = id?.let(::movieCmd))
        return Decision.Cmd(cmd)   // XC emulator: base64 / opaque cmd goes through unchanged
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
    private val RAW_URL = Regex("""^(?:https?|rtsp|rtp|udp)://""", RegexOption.IGNORE_CASE)
}
