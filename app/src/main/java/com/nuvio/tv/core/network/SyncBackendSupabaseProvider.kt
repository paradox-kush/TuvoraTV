package com.nuvio.tv.core.network

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.auth.shouldRetryAuthRefreshResponse
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncBackendSupabaseProvider @Inject constructor(
    private val syncBackendRepository: SyncBackendRepository,
) {
    private data class ClientHolder(
        val backend: SyncBackendConfig,
        val client: SupabaseClient,
    )

    @Volatile
    private var clientHolder: ClientHolder? = null

    val selectedBackend: SyncBackendConfig
        get() = syncBackendRepository.selectedBackend

    val client: SupabaseClient
        get() = clientFor(selectedBackend)

    val auth: Auth
        get() = client.auth

    val postgrest: Postgrest
        get() = client.postgrest

    @Synchronized
    fun rebuildClient() {
        clientHolder = null
    }

    @Synchronized
    @OptIn(SupabaseInternal::class)
    private fun clientFor(backend: SyncBackendConfig): SupabaseClient {
        clientHolder
            ?.takeIf { holder -> holder.backend.hasSameConnectionIdentity(backend) }
            ?.let { return it.client }

        val userAgent = "NuvioTV/${BuildConfig.VERSION_NAME.ifBlank { "dev" }}"
        val client = createSupabaseClient(
            supabaseUrl = backend.normalizedSupabaseUrl,
            supabaseKey = backend.anonKey,
        ) {
            httpConfig {
                // supabase-kt retries a failed refresh only for its NETWORK_ERROR_CODES (5xx) and
                // for failures that never reached the server. Every other failing status falls
                // through to clearSession(), which deletes the persisted session — the box then
                // comes back signed out with "No entry with the key sb-<ref>-session". Absorbing
                // the transient refusals here means the library never sees them.
                install(HttpRequestRetry) {
                    retryIf(maxRetries = 2) { request, response ->
                        shouldRetryAuthRefreshResponse(
                            statusCode = response.status.value,
                            path = request.url.encodedPath,
                            grantType = request.url.parameters["grant_type"],
                            server = response.headers[HttpHeaders.Server],
                            cloudflareRay = response.headers["cf-ray"],
                        )
                    }
                    // Deliberately short and few: the retry has to land inside GoTrue's
                    // refresh-token reuse interval, and re-presenting the token after that window
                    // trips reuse detection and revokes the whole family.
                    constantDelay(millis = 100)
                }
                defaultRequest {
                    headers.append(HttpHeaders.UserAgent, userAgent)
                }
            }
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            // Backs upstream's realtime sync-invalidation service.
            install(Realtime)
        }
        clientHolder = ClientHolder(backend = backend, client = client)
        return client
    }
}
