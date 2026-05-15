package com.nuvio.tv.core.debrid

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.DirectDebridStreamApi
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

private const val DIRECT_DEBRID_TAG = "DirectDebridStreams"

@Singleton
class DirectDebridStreamSource @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val api: DirectDebridStreamApi,
    private val encoder: DirectDebridConfigEncoder,
    private val formatter: DebridStreamFormatter
) {
    suspend fun isEnabled(): Boolean {
        return sourceNames().isNotEmpty()
    }

    suspend fun sourceNames(): List<String> {
        val settings = dataStore.settings.first()
        if (!settings.enabled || BuildConfig.DIRECT_DEBRID_API_BASE_URL.isBlank()) return emptyList()
        return DebridProviders.configuredSourceNames(settings)
    }

    suspend fun fetchStreams(type: String, videoId: String): DirectDebridStreamFetchResult {
        val settings = dataStore.settings.first()
        val services = DebridProviders.configuredServices(settings)
        val baseUrl = BuildConfig.DIRECT_DEBRID_API_BASE_URL.trim().trimEnd('/')
        if (!settings.enabled || services.isEmpty() || baseUrl.isBlank()) {
            return DirectDebridStreamFetchResult.Disabled
        }

        val results = mutableListOf<AddonStreams>()
        val errors = mutableListOf<String>()
        services.forEach { service ->
            when (val result = fetchProviderStreams(baseUrl, type, videoId, service, settings)) {
                is ProviderFetchResult.Success -> results += result.streams
                is ProviderFetchResult.Error -> errors += result.message
                ProviderFetchResult.Empty -> Unit
            }
        }

        return when {
            results.isNotEmpty() -> DirectDebridStreamFetchResult.Success(results)
            errors.isNotEmpty() -> DirectDebridStreamFetchResult.Error(errors.first())
            else -> DirectDebridStreamFetchResult.Empty
        }
    }

    private suspend fun fetchProviderStreams(
        baseUrl: String,
        type: String,
        videoId: String,
        service: DebridServiceCredential,
        settings: DebridSettings
    ): ProviderFetchResult {
        val b64Config = encoder.encode(service)
        val url = "$baseUrl/$b64Config/client-stream/${encodePathSegment(type)}/${encodePathSegment(videoId)}.json"
        return try {
            val response = api.getClientStreams(url)
            if (response.isSuccessful) {
                val streams = response.body()?.streams
                    ?.map { it.toDomain(DirectDebridStreamFilter.FALLBACK_SOURCE_NAME, null) }
                    ?.let(DirectDebridStreamFilter::filterInstant)
                    ?.map { formatter.format(it, settings) }
                    .orEmpty()
                if (streams.isEmpty()) {
                    ProviderFetchResult.Empty
                } else {
                    ProviderFetchResult.Success(
                        streams.groupBy { it.addonName }
                            .map { (addonName, groupedStreams) ->
                                AddonStreams(
                                    addonName = addonName,
                                    addonLogo = null,
                                    streams = groupedStreams
                                )
                            }
                    )
                }
            } else {
                val message = response.message().ifBlank { "HTTP ${response.code()}" }
                Log.w(
                    DIRECT_DEBRID_TAG,
                    "Direct debrid ${service.provider.id} request failed code=${response.code()} message=$message"
                )
                ProviderFetchResult.Error(message)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            val message = error.message ?: "Unknown error"
            Log.w(DIRECT_DEBRID_TAG, "Direct debrid ${service.provider.id} request failed message=$message")
            ProviderFetchResult.Error(message)
        }
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

private sealed class ProviderFetchResult {
    data object Empty : ProviderFetchResult()
    data class Success(val streams: List<AddonStreams>) : ProviderFetchResult()
    data class Error(val message: String) : ProviderFetchResult()
}

sealed class DirectDebridStreamFetchResult {
    data object Disabled : DirectDebridStreamFetchResult()
    data object Empty : DirectDebridStreamFetchResult()
    data class Success(val streams: List<AddonStreams>) : DirectDebridStreamFetchResult()
    data class Error(val message: String) : DirectDebridStreamFetchResult()
}
