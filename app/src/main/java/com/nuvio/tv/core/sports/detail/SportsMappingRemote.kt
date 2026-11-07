package com.nuvio.tv.core.sports.detail

object SportsMappingRemote {
    /**
     * The one place the hosted mapping URL lives. PLACEHOLDER until the backend wave hosts
     * `sports-mapping.json` (Supabase Storage/edge, ETag-cached); change here only.
     */
    const val URL = "https://tuvora.co/sports-mapping.json"
}

sealed interface RemoteMapping {
    data class Updated(val body: String, val etag: String?) : RemoteMapping
    data object NotModified : RemoteMapping
    data object Failed : RemoteMapping
}

interface MappingRemote {
    suspend fun fetch(etag: String?): RemoteMapping
}

class HttpMappingRemote(private val http: HttpFetcher, private val deadlineMs: Long = 8_000) : MappingRemote {
    override suspend fun fetch(etag: String?): RemoteMapping = try {
        val headers = buildMap { put("Accept", "application/json"); if (etag != null) put("If-None-Match", etag) }
        val r = http.get(SportsMappingRemote.URL, headers, deadlineMs)
        when {
            r.code == 304 -> RemoteMapping.NotModified
            r.isSuccessful && r.body != null -> RemoteMapping.Updated(r.body, r.etag)
            else -> RemoteMapping.Failed
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Exception) {
        RemoteMapping.Failed
    }
}

/**
 * Pulls the hosted mapping with its ETag (a 304 costs no body), offers it to the store, and
 * remembers the ETag. Call at app start and then rarely (daily). Returns the store's verdict, or
 * null when nothing was offered (not modified / network failure). Never throws.
 */
class SportsMappingUpdater(
    private val store: SportsMappingStore,
    private val remote: MappingRemote,
    private val persistence: MappingPersistence,
) {
    suspend fun refresh(): OfferResult? = when (val r = remote.fetch(persistence.loadEtag())) {
        is RemoteMapping.Updated -> store.offer(r.body).also { persistence.saveEtag(r.etag) }
        RemoteMapping.NotModified, RemoteMapping.Failed -> null
    }
}
