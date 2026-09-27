package com.nuvio.tv.core.iptv

import com.nuvio.tv.core.contracts.IptvStreamSources
import com.nuvio.tv.data.local.XtreamAccountStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Fork implementation of [IptvStreamSources], derived from the active profile's enabled IPTV accounts. */
class XtreamIptvStreamSources @Inject constructor(
    accountStore: XtreamAccountStore,
) : IptvStreamSources {
    override val servedContentTypes: Flow<Set<String>> = accountStore.accounts.map(::servedContentTypesOf)

    companion object {
        fun servedContentTypesOf(accounts: List<XtreamAccount>): Set<String> =
            accounts.filter { it.enabled }.flatMap { account ->
                buildList {
                    if (XtreamAccount.TYPE_MOVIES in account.contentTypes) add("movie")
                    if (XtreamAccount.TYPE_SERIES in account.contentTypes) add("series")
                }
            }.toSet()
    }
}
