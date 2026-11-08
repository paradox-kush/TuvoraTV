package com.nuvio.tv.core.sports.detail

/** Backend function names (directories under supabase/functions), appended to the functions base. The only place they live. */
object SportsDetailPaths {
    const val GAME_DETAIL = "game-detail"
    const val SPORTS_MAPPING = "sports-mapping"
    const val MARKET_WIN_CHANCE = "market-winchance"
}

sealed interface RemoteMapping {
    data class Updated(val body: String, val etag: String?) : RemoteMapping
    data object NotModified : RemoteMapping
    data object Failed : RemoteMapping
}

interface MappingRemote {
    suspend fun fetch(etag: String?): RemoteMapping
}

/** GET {functionsBase}/sports-mapping with If-None-Match; a 304 costs no body. */
class HttpMappingRemote(
    private val http: HttpFetcher,
    private val functionsBase: () -> String?,
    private val deadlineMs: Long = 8_000,
) : MappingRemote {
    override suspend fun fetch(etag: String?): RemoteMapping = try {
        val base = functionsBase()?.trimEnd('/')
        if (base == null) RemoteMapping.Failed else {
            val headers = buildMap { put("Accept", "application/json"); if (etag != null) put("If-None-Match", etag) }
            val r = http.get("$base/${SportsDetailPaths.SPORTS_MAPPING}", headers, deadlineMs)
            when {
                r.code == 304 -> RemoteMapping.NotModified
                r.isSuccessful && r.body != null -> RemoteMapping.Updated(r.body, r.etag)
                else -> RemoteMapping.Failed
            }
        }
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Exception) {
        RemoteMapping.Failed
    }
}

/**
 * Pulls the hosted mapping with its ETag, offers it to the store, and remembers the ETag. Call at
 * screen open (cheap: a 304) or daily. Returns the store's verdict, or null when nothing was offered
 * (not modified / network failure). Never throws.
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
