package com.nuvio.tv.ui.screens.iptv

import com.nuvio.tv.core.iptv.M3uCatchUpInfo
import com.nuvio.tv.core.iptv.M3uCatchUpType
import com.nuvio.tv.core.recordings.api.RecordingCatchUp
import com.nuvio.tv.core.recordings.api.RecordingChannel

/**
 * Builds the channel snapshot a recording keeps (so a save can start without Live TV loaded) from the
 * facts the guide already has. Pure: [LiveTvData] reads the facts, this decides what they mean.
 */
internal object RecordingChannelFactory {
    /** `tv_archive` set but the panel gave no window (`tv_archive_duration` is often absent): assume this many days. */
    const val DEFAULT_ARCHIVE_DAYS = 3

    /** The stream's own request headers: the playlist's User-Agent override rides along (see StreamUserAgentPolicy). */
    fun headers(userAgent: String?): Map<String, String> = TODO("wave 3")

    /** The `catchup-type` word that `M3uCatchUp.catchUpType` reads back to the same type. */
    fun m3uTypeName(type: M3uCatchUpType): String = TODO("wave 3")

    fun xtream(
        contentId: String,
        accountId: String,
        name: String,
        logo: String?,
        liveUrl: String,
        userAgent: String?,
        baseUrl: String,
        username: String,
        password: String,
        streamId: Int,
        hasArchive: Boolean,
        catchUpDays: Int,
        serverOffsetMs: Long?,
    ): RecordingChannel = TODO("wave 3")

    fun m3u(
        contentId: String,
        accountId: String,
        name: String,
        logo: String?,
        liveUrl: String,
        userAgent: String?,
        info: M3uCatchUpInfo?,
        offsetMs: Long,
    ): RecordingChannel = TODO("wave 3")

    /** A playlist type with no catch-up we can save from (Stalker links are single-use). */
    fun withoutCatchUp(
        contentId: String,
        accountId: String,
        name: String,
        logo: String?,
        liveUrl: String,
        userAgent: String?,
    ): RecordingChannel = TODO("wave 3")
}
