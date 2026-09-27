package com.nuvio.tv.core.contracts

import kotlinx.coroutines.flow.Flow

/**
 * Neutral port: the Stremio content types ("movie", "series") the user's IPTV sources can supply streams
 * for through the Xtream source lane. Upstream-aligned code (e.g. play availability) reads this instead of
 * naming the fork's IPTV account store.
 */
interface IptvStreamSources {
    val servedContentTypes: Flow<Set<String>>
}
