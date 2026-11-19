package com.nuvio.tv.core.contracts

/** Neutral port: "this live channel was just played". The IPTV feature supplies the implementation (composition root). */
interface LivePlayRecorder {
    /** A play by [profileId] (the profile that was watching, not whoever is active now). */
    suspend fun recordPlay(profileId: Int, channelId: String)

    /** A play by the active profile. */
    suspend fun recordPlay(channelId: String)
}
