package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProviderCapability
import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.core.debrid.DebridServiceCredential
import com.nuvio.tv.core.debrid.supports
import com.nuvio.tv.data.local.DebridSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class CloudLibraryRepository @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    torboxApi: TorboxCloudLibraryProviderApi,
    premiumizeApi: PremiumizeCloudLibraryProviderApi
) {
    private val providerApis: List<CloudLibraryProviderApi> = listOf(torboxApi, premiumizeApi)

    suspend fun refresh(): CloudLibraryUiState {
        val settings = dataStore.settings.first()
        if (!settings.cloudLibraryEnabled) {
            return CloudLibraryUiState(isLoaded = true, isEnabled = false)
        }

        val credentials = DebridProviders.configuredServices(settings)
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }

        val providerStates = credentials.map { credential ->
            val api = providerApis.firstOrNull { it.provider.id == credential.provider.id }
            if (api == null) {
                return@map CloudLibraryProviderState(
                    provider = credential.provider,
                    errorMessage = "Cloud library is not available for ${credential.provider.displayName}."
                )
            }

            api.listItems(credential.apiKey)
                .fold(
                    onSuccess = { items ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            items = items
                        )
                    },
                    onFailure = { error ->
                        CloudLibraryProviderState(
                            provider = credential.provider,
                            errorMessage = error.message
                        )
                    }
                )
        }

        return CloudLibraryUiState(
            isLoaded = true,
            isEnabled = true,
            isRefreshing = false,
            providers = providerStates
        )
    }

    suspend fun resolvePlayback(
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        val settings = dataStore.settings.first()
        if (!settings.cloudLibraryEnabled) {
            return CloudLibraryPlaybackResult.Failed("Cloud library is disabled.")
        }
        val credential = DebridProviders.configuredServices(settings)
            .firstOrNull { credential -> credential.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.MissingCredentials
        val api = providerApis.firstOrNull { it.provider.id == item.providerId }
            ?: return CloudLibraryPlaybackResult.Failed()
        return api.resolvePlayback(
            apiKey = credential.apiKey,
            item = item,
            file = file
        )
    }

    suspend fun connectedCloudCredentials(): List<DebridServiceCredential> {
        val settings = dataStore.settings.first()
        return settings
            .takeIf { it.cloudLibraryEnabled }
            ?.let(DebridProviders::configuredServices)
            .orEmpty()
            .filter { credential -> credential.provider.supports(DebridProviderCapability.CloudLibrary) }
    }
}
