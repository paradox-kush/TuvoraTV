package com.nuvio.tv.core.iptv.home

import com.nuvio.tv.core.contracts.HomeLiveRecent
import com.nuvio.tv.core.contracts.HomeLiveRecentsProvider
import com.nuvio.tv.core.iptv.XtreamItemRegistry
import com.nuvio.tv.core.iptv.epg.XmltvClient
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.data.local.XtreamLiveStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The Home "Recent Channels" row's data: the profile's recently watched live channels plus what is on now
 * where the guide is ALREADY stored on this device.
 *
 * It never fetches. A guide lookup here reads the local guide database only, so showing the row costs no
 * network and no provider request (see the recurring-network-work rule).
 */
@Singleton
class IptvHomeLiveRecents @Inject constructor(
    private val liveStore: XtreamLiveStore,
    private val accountStore: XtreamAccountStore,
    private val xmltv: XmltvClient,
) : HomeLiveRecentsProvider {
    /** Emits whenever the recents change; each emission re-reads the stored guide for "on now". */
    override val recents: Flow<List<HomeLiveRecent>> = liveStore.recents.map { refs -> build(refs) }

    /** The same list, re-read now (what is on changes while the recents do not). */
    override suspend fun snapshot(): List<HomeLiveRecent> = build(liveStore.recents.first())

    private suspend fun build(refs: List<com.nuvio.tv.data.local.LiveChannelRef>): List<HomeLiveRecent> {
        val chosen = HomeLiveRecentsPolicy.select(refs.map { HomeLiveRecentsPolicy.Candidate(it.id, it.name, it.logo) })
        if (chosen.isEmpty()) return emptyList()
        val accounts = runCatching { accountStore.accounts.first() }.getOrDefault(emptyList())
        val nowMs = System.currentTimeMillis()
        return chosen.map { channel ->
            HomeLiveRecent(
                contentId = channel.contentId,
                name = channel.name,
                logo = channel.logo?.takeIf { it.isNotBlank() },
                nowTitle = nowTitleFor(channel.contentId, accounts, nowMs),
            )
        }
    }

    private suspend fun nowTitleFor(
        contentId: String,
        accounts: List<com.nuvio.tv.core.iptv.XtreamAccount>,
        nowMs: Long,
    ): String? {
        val parsed = XtreamItemRegistry.parseId(contentId) ?: return null
        val streamId = parsed.streamId.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val account = accounts.firstOrNull { it.id == parsed.accountId } ?: return null
        return try {
            HomeLiveRecentsPolicy.nowTitle(xmltv.storedNowNext(account, streamId, nowMs), nowMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
