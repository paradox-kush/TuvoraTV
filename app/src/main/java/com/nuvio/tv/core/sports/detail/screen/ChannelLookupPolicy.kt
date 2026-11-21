package com.nuvio.tv.core.sports.detail.screen

/**
 * Pure: whether the game screen's bounded channel lookup ([WatchResolver.LOOKUP_DEADLINE_MS]) gets a second attempt.
 *
 * Only a cold start earns one: the first attempt ran out of time while a playlist's lineup was still downloading, and
 * the download keeps running in the matcher's own scope, so the second attempt finds it ready. Any other timeout
 * would repeat the same slow work and double the wait before "Couldn't check your channels" (on the Onn every open
 * took ~13 s to fail that way). Matches already found always stand: they are shown, never replaced by a retry.
 */
object ChannelLookupPolicy {
    fun retryAfterTimeout(matchesSoFar: Int, lineupsDownloading: Int): Boolean =
        matchesSoFar == 0 && lineupsDownloading > 0
}
