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
    /** [skipAddons]: store flavours must not install the package's add-ons (see [RedeemAddonsPolicy]). */
    suspend fun redeem(code: String, profileIndex: Int, skipAddons: Boolean = false): RedeemOutcome
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

    /** The preview route's answer is a few hundred bytes; anything over this is not it. */
    const val MAX_PREVIEW_BYTES = 64L * 1024

    /** The body as text, or null when it is longer than [maxBytes] (read no further than one byte past it). */
    fun readCapped(source: okio.BufferedSource?, maxBytes: Long = MAX_PREVIEW_BYTES): String? {
        if (source == null) return null
        if (!source.request(maxBytes + 1)) return source.readUtf8() // fewer bytes than the cap + 1: all of it
        return null
    }

    /** The server's raised error codes (the whole first line of a PostgREST message is exactly one of these). */
    private val KNOWN_CODES = setOf(
        "anonymous_not_allowed", "not_authenticated", "profile_not_found", "rate_limited", "expired",
        "already_used", "invalid_code", "not_found", "unavailable", "suspended", "revoked", "used",
    )

    /**
     * A failed RPC -> outcome. The raised code is the WHOLE first line of the message (`[a-z_]+`), never a
     * substring (a "refused" would contain "used"). A session the server no longer accepts (401/403, "JWT
     * expired") needs sign-in; it is not an expired CODE. A 5xx, a transport failure or anything we cannot read is
     * [SetupCodeOutcome.Network]: no verdict on the code, so it is kept and can be retried.
     */
    fun rpcFailure(status: Int?, message: String?): SetupCodeOutcome {
        val first = message.orEmpty().trim().lineSequence().firstOrNull().orEmpty().trim().lowercase()
        val code = first.takeIf { it.isNotEmpty() && it.all { c -> c in 'a'..'z' || c == '_' } }
        if (code != null && code in KNOWN_CODES) return SetupCodeOutcome.forServerCode(code)
        // A raised exception is an HTTP 400. If the code is not the bare first line (a wrapped message), take it
        // only as a WHOLE token ("refused" is not "used"; this is never tried for a 401 "JWT expired").
        if (status == 400) {
            val token = message.orEmpty().lowercase().split(Regex("[^a-z_]+")).firstOrNull { it in KNOWN_CODES }
            if (token != null) return SetupCodeOutcome.forServerCode(token)
        }
        return when {
            status == 401 || status == 403 -> SetupCodeOutcome.NeedsSignIn
            status != null && status in 500..599 -> SetupCodeOutcome.Network
            status == null -> SetupCodeOutcome.Network
            code != null -> SetupCodeOutcome.forServerCode(code) // an unknown raised code: one neutral answer
            else -> SetupCodeOutcome.Unusable
        }
    }

    /** A thrown exception -> outcome: transport failures are [SetupCodeOutcome.Network]; Rest errors use [rpcFailure]. */
    fun forThrowable(e: Throwable): SetupCodeOutcome {
        if (e is IOException) return SetupCodeOutcome.Network
        if (e is io.github.jan.supabase.exceptions.RestException) return rpcFailure(e.statusCode, e.message)
        return SetupCodeOutcome.Network
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
                skippedReasons = playlists.filter { (it["action"] as? JsonPrimitive)?.content == "skipped" }
                    .map { (it["reason"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "missing_login" },
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
            // Default (true): a stale pooled keep-alive connection is retried transparently. The preview is an idempotent
            // GET whose request never reached the server in that case, so it cannot cost a rate-limit strike.
            .retryOnConnectionFailure(true)
            // The route never redirects. A redirect could carry the code (query) and the bearer token elsewhere.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    override suspend fun preview(code: String, accessToken: String?): SetupCodeOutcome = withContext(Dispatchers.IO) {
        try {
            val url = ProviderSetupConfig.previewUrl(code)
            val request = Request.Builder().url(url).get()
                .apply { accessToken?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .build()
            previewClient.newCall(request).execute().use { response ->
                val text = SetupResponses.readCapped(response.body?.source())
                if (text == null && response.code in 200..299) SetupCodeOutcome.Unusable
                else SetupResponses.parsePreview(response.code, text, response.header("Retry-After"))
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

    override suspend fun redeem(code: String, profileIndex: Int, skipAddons: Boolean): RedeemOutcome = withContext(Dispatchers.IO) {
        try {
            val params = buildJsonObject {
                put("p_code", code)
                put("p_profile_index", profileIndex)
                // Sent only when true: an older backend without the argument keeps working for the full flavour.
                if (skipAddons) put("p_skip_addons", true)
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
    const val PRODUCTION_BASE = "https://tuvora.co"

    /**
     * The override (`PROVIDER_SETUP_BASE_URL`, a local-testing aid) is honoured ONLY in a debug build
     * (`IS_DEBUG_BUILD`: TV debug has `BuildConfig.DEBUG == false`). A release build always uses [PRODUCTION_BASE]
     * (https), so a stray `local.properties` value can never redirect the code or the token.
     */
    fun resolveBase(override: String, isDebug: Boolean): String {
        val o = override.trim().trimEnd('/')
        return if (isDebug && o.isNotEmpty()) o else PRODUCTION_BASE
    }

    /** The apex that serves the claim page and the preview route. */
    val BASE_URL: String get() = resolveBase(BuildConfig.PROVIDER_SETUP_BASE_URL, BuildConfig.IS_DEBUG_BUILD)

    /** Where the code screen's QR points: the claim page's entry. */
    val CLAIM_ENTRY_URL: String get() = "$BASE_URL/s"

    /** The access token goes only to the hosted backend's own web (https; plain http only in a debug build). */
    fun sendsToken(backendIsHosted: Boolean, baseUrl: String, isDebug: Boolean): Boolean =
        backendIsHosted && (baseUrl.startsWith("https://") || (isDebug && baseUrl.startsWith("http://")))

    fun previewUrl(code: String, base: String = BASE_URL) = base.trimEnd('/').toHttpUrl().newBuilder()
        .addPathSegments("api/s/preview")
        .addQueryParameter("code", SetupCode.format(code))
        .build()
}

/**
 * Store flavours (add-ons hidden: `AppFeaturePolicy.addonsEnabled == false`) must not receive the package's
 * add-ons from a redeem: the person cannot see or consent to them. Decision 6.5 / security M6.
 */
object RedeemAddonsPolicy {
    fun skipAddons(addonsEnabled: Boolean): Boolean = !addonsEnabled
}
