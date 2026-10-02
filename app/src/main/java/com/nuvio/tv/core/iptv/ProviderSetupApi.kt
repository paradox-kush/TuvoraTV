package com.nuvio.tv.core.iptv

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The result of `redeem_setup`. */
sealed interface RedeemOutcome {
    data class Done(val result: RedeemResult) : RedeemOutcome
    data class Failed(val outcome: SetupCodeOutcome) : RedeemOutcome
}

/**
 * Step 2 client API (behind an interface so the UI and the tests use fakes).
 *
 * The setup code is a secret: no method here logs it, and the preview is fetched with a client that has
 * NO cache and NO interceptors (the app's default client caches responses on disk and feeds URLs to
 * Sentry breadcrumbs, either of which would keep the code).
 */
interface ProviderSetupApi {
    /** [code] is the normalized 12 characters. [accessToken] gives the signed-in per-account rate-limit bucket. */
    suspend fun preview(code: String, accessToken: String?): SetupCodeOutcome
    suspend fun redeem(code: String, profileIndex: Int): RedeemOutcome
    suspend fun managedPlaylists(profileId: Int): Result<List<ManagedPlaylistInfo>>
    suspend fun detach(profileId: Int, playlistKey: String): Result<Boolean>
}

/** Pure mapping of the preview route's HTTP answer and of raised/transport failures (unit-tested). */
object SetupResponses {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parsePreview(status: Int, body: String?, retryAfterHeader: String? = null): SetupCodeOutcome {
        val root = runCatching { body?.let { json.parseToJsonElement(it) } }.getOrNull()
        if (status in 200..299) {
            val preview = SetupPreview.fromJson(root) ?: return SetupCodeOutcome.Unusable
            return SetupCodeOutcome.Ready(preview)
        }
        val code = ((root as? JsonObject)?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val retry = retryAfterHeader?.trim()?.toIntOrNull()?.takeIf { it >= 0 }
        return SetupCodeOutcome.forPreviewHttp(status, code, retry)
    }

    private val KNOWN_CODES = listOf(
        "anonymous_not_allowed", "not_authenticated", "profile_not_found", "rate_limited", "expired",
        "already_used", "invalid_code", "not_found", "unavailable", "suspended", "revoked", "used",
    )

    /** A raised RPC error or a transport failure -> outcome. Transport failures are [SetupCodeOutcome.Network]. */
    fun forThrowable(e: Throwable): SetupCodeOutcome {
        if (e is IOException) return SetupCodeOutcome.Network
        val msg = e.message.orEmpty().lowercase()
        val code = KNOWN_CODES.firstOrNull { it in msg }
        if (code != null) return SetupCodeOutcome.forServerCode(code)
        val transport = listOf("unable to resolve host", "timeout", "timed out", "connect", "network", "socket")
        return if (transport.any { it in msg }) SetupCodeOutcome.Network else SetupCodeOutcome.Unusable
    }

    fun parseRedeem(body: JsonElement?): RedeemOutcome {
        val o = body as? JsonObject ?: return RedeemOutcome.Failed(SetupCodeOutcome.Unusable)
        val ok = (o["ok"] as? JsonPrimitive)?.content == "true"
        if (!ok) {
            val err = (o["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return RedeemOutcome.Failed(SetupCodeOutcome.forServerCode(err))
        }
        fun int(k: String) = (o[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        val playlists = (o["playlists"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return RedeemOutcome.Done(
            RedeemResult(
                status = (o["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "redeemed",
                profileIndex = (o["profile_index"] as? JsonPrimitive)?.content?.toIntOrNull(),
                added = int("added"), updated = int("updated"), unchanged = int("unchanged"),
                playlistKeys = playlists.mapNotNull { (it["playlist_key"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
                playlistNames = playlists.mapNotNull { (it["name"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            )
        )
    }
}

@Singleton
class SupabaseProviderSetupApi @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
) : ProviderSetupApi {

    private val postgrest get() = supabaseProvider.postgrest

    /** No cache, no interceptors, 15 s end to end (contract section 2). */
    private val previewClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    override suspend fun preview(code: String, accessToken: String?): SetupCodeOutcome = withContext(Dispatchers.IO) {
        try {
            val url = ProviderSetupConfig.previewUrl(code)
            val request = Request.Builder().url(url).get()
                .apply { accessToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .build()
            previewClient.newCall(request).execute().use { response ->
                SetupResponses.parsePreview(response.code, response.body?.string(), response.header("Retry-After"))
            }
        } catch (e: IOException) {
            SetupCodeOutcome.Network
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never log the exception text: an OkHttp message can quote the request URL (and the code).
            Log.w(TAG, "preview failed: ${e.javaClass.simpleName}")
            SetupResponses.forThrowable(e)
        }
    }

    override suspend fun redeem(code: String, profileIndex: Int): RedeemOutcome = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_code", code)
                put("p_profile_index", profileIndex)
            }
            val body: JsonElement = postgrest.rpc("redeem_setup", params).decodeAs<JsonElement>()
            SetupResponses.parseRedeem(body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "redeem failed: ${e.javaClass.simpleName}")
            RedeemOutcome.Failed(SetupResponses.forThrowable(e))
        }
    }

    override suspend fun managedPlaylists(profileId: Int): Result<List<ManagedPlaylistInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val body: JsonElement = postgrest.rpc("get_managed_playlists", buildJsonObject { put("p_profile_id", profileId) })
                .decodeAs<JsonElement>()
            ManagedPlaylistInfo.listFromJson(body)
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    override suspend fun detach(profileId: Int, playlistKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val body: JsonElement = postgrest.rpc(
                "detach_managed_playlist",
                buildJsonObject { put("p_profile_id", profileId); put("p_playlist_key", playlistKey) }
            ).decodeAs<JsonElement>()
            (((body as? JsonObject)?.get("detached")) as? JsonPrimitive)?.content == "true"
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
    }

    private companion object {
        const val TAG = "ProviderSetupApi"
    }
}

/** The web hosts in ONE place (contract section 2: "make the base URL a single constant"). */
object ProviderSetupConfig {
    /** The apex that serves the claim page and the preview route. */
    val BASE_URL: String get() = BuildConfig.PROVIDER_SETUP_BASE_URL.trimEnd('/')

    /** Where the code screen's QR points: the claim page's entry. */
    val CLAIM_ENTRY_URL: String get() = "$BASE_URL/s"

    fun previewUrl(code: String, base: String = BASE_URL) = base.trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegments("api/s/preview")
        .addQueryParameter("code", SetupCode.format(code))
        .build()
}
