package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface AddonRepository {
    fun getInstalledAddons(): Flow<List<Addon>>
    suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addAddon(url: String)
    suspend fun removeAddon(url: String)
    suspend fun setAddonOrder(urls: List<String>)
    suspend fun setAddonEnabled(url: String, enabled: Boolean)

    /**
     * Installed, enabled add-ons whose manifest failed and that no automatic retry is still working
     * on - what Home reports as "some rows didn't load". Defaults to none for test doubles.
     */
    fun unresolvedManifestFailures(): Flow<Set<String>> = flowOf(emptySet())

    /** Fetches again only the enabled add-ons whose manifest failed; no request at all when none did. */
    fun retryFailedManifests() {}
}
