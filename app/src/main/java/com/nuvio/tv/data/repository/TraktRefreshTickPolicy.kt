package com.nuvio.tv.data.repository

/**
 * Whether the 60 s Trakt refresh tick makes a request. No connected account: nothing to fetch (it only logged
 * a failure per tick). Backgrounded: nobody sees the result and the backend still bills it. The first fetch and
 * explicit refresh signals are not ticks and are unaffected.
 */
internal object TraktRefreshTickPolicy {
    fun shouldTick(accountConnected: Boolean, appInForeground: Boolean): Boolean =
        accountConnected && appInForeground
}
