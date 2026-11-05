package com.nuvio.tv.core.iptv.stalker

import com.nuvio.tv.core.iptv.CatchUpFailure.StalkerLinkError

/**
 * The wire shape of a Stalker/Ministra `create_link`, and of the archive (catch-up) one in particular.
 *
 * Replaying a past programme is `type=tv_archive` with a cmd built from the portal's own EPG
 * programme id - `auto /media/<id>.mpg` (Ministra c/tv_archive.js) - and the server derives the time
 * range from it. There is no `utc`/`lutc` form on Stalker. Pure so the request and the three
 * documented refusals test without a portal.
 */
object StalkerArchiveLink {

    fun cmd(programmeId: String): String = "auto /media/$programmeId.mpg"

    /** Params every `create_link` carries; the session adds `JsHttpRequest` itself. */
    fun createLinkParams(type: String, cmd: String, extra: Map<String, String>): Map<String, String> = buildMap {
        put("type", type)
        put("action", "create_link")
        put("cmd", cmd)
        put("forced_storage", "undefined")
        put("disable_ad", "0")
        putAll(extra)
    }

    /** The whole archive request for one programme. */
    fun params(programmeId: String): Map<String, String> =
        createLinkParams("tv_archive", cmd(programmeId), ARCHIVE_EXTRA)

    /** Extra params of an archive request: a stream link, not a download. */
    val ARCHIVE_EXTRA: Map<String, String> = mapOf("download" to "0")

    /** The viewer-facing words for a refusal; null when the answer was not a refusal. */
    fun messageFor(error: StalkerLinkError): String? = when (error) {
        StalkerLinkError.SESSION_LIMIT -> SESSION_LIMIT_MESSAGE
        StalkerLinkError.NOTHING_TO_PLAY -> NOTHING_TO_PLAY_MESSAGE
        StalkerLinkError.LINK_FAULT -> ARCHIVE_LINK_FAULT_MESSAGE
        StalkerLinkError.NONE -> null
    }

    const val SESSION_LIMIT_MESSAGE = "This portal is already streaming on another device — close it and try again"
    const val NOTHING_TO_PLAY_MESSAGE = "The portal has no recording of this programme"
    const val ARCHIVE_LINK_FAULT_MESSAGE = "The portal couldn't build a playback link for this programme"
}
