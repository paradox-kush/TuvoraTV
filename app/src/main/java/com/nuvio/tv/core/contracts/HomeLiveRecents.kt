package com.nuvio.tv.core.contracts

import kotlinx.coroutines.flow.Flow

/** What the Home "Recent Channels" row shows for one channel. [nowTitle] is only set when the guide already knows it. */
data class HomeLiveRecent(
    val contentId: String,
    val name: String,
    val logo: String?,
    val nowTitle: String?,
)

/**
 * Neutral port for the Home Recent Channels row. Home asks for the items; the Live TV feature implements it
 * from local data only (no fetch) and it is bound at the composition root.
 */
interface HomeLiveRecentsProvider {
    /** Emits whenever the recents change; each emission re-reads what is on now. */
    val recents: Flow<List<HomeLiveRecent>>

    /** The same list, read now. */
    suspend fun snapshot(): List<HomeLiveRecent>
}
