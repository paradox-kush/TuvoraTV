package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.iptv.XtreamAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** The portal rejected our identity/token — the ONLY failure that may trigger a re-handshake. */
class StalkerAuthException(message: String) : IllegalStateException(message)

/**
 * A stateful Stalker-portal (MAG/Ministra) session for ONE playlist. Owns:
 *  - endpoint probing (the user enters just a base portal URL; we try [StalkerProtocol.ENDPOINT_CANDIDATES]
 *    in order and remember the first that handshakes),
 *  - the auth token from `handshake` + the device identity from `get_profile`,
 *  - a single-flight (re-)authenticate so concurrent browse calls don't stampede the portal,
 *  - [request] — an authenticated GET that transparently re-handshakes + retries once on an expired
 *    token / empty `js` / 401 / 403.
 *
 * create_link is NOT done here (it's a per-play call in [StalkerClient]) — but every call routes
 * through [request] so it inherits the auth + retry-once behaviour. There is no keep-alive: an idle
 * session simply re-handshakes on demand the next time it's used, like a real STB after it sleeps.
 *
 * Thread-safe: [authMutex] serialises (re-)auth; the resolved token/endpoint are @Volatile so browse
 * calls read the freshest values without holding the lock.
 */
class StalkerSession(
    private val account: XtreamAccount,
    private val http: OkHttpClient
) {
    @Volatile private var token: String? = null
    @Volatile private var resolvedEndpoint: String? = null   // e.g. "/portal.php"

    private val authMutex = Mutex()

    // Hard ceiling on concurrent requests to this portal. A real MAG box opens a couple of
    // connections; magplex (the reference client) caps this at 3 explicitly "to prevent rate
    // limiting". Ours is per-session so a busy UI can't fan out into a ban.
    private val gate = Semaphore(MAX_CONCURRENT_REQUESTS)

    private val baseUrl: String = StalkerProtocol.normalizePortalBase(account.portalUrl)
    private val identity: StalkerProtocol.DeviceIdentity =
        StalkerProtocol.deriveDeviceIdentity(
            mac = account.macAddress,
            serialOverride = account.serialNumber,
            deviceIdOverride = account.deviceId
        )

    /** The `.../c/` Referer for the currently-resolved endpoint (falls back to the first candidate). */
    private val referer: String
        get() = StalkerProtocol.refererFor(baseUrl, resolvedEndpoint ?: StalkerProtocol.ENDPOINT_CANDIDATES.first())

    /**
     * Authenticated Stalker GET. [params] are the JsHttpRequest query params (type/action/…); the
     * token cookie/header + `&JsHttpRequest=1-xml` are added here. Returns the `js` element of the
     * `{"js": …}` envelope. Re-handshakes + retries ONCE on a stale token (empty body / empty js /
     * 401 / 403). Throws on a hard failure so callers' [runCatching] degrades to empty.
     */
    suspend fun request(params: Map<String, String>): JsonElement = withContext(Dispatchers.IO) {
        ensureAuthenticated()
        val staleToken = token
        // ONLY an auth failure earns a re-handshake. A transport/HTTP throw (429/419/5xx/timeout) must
        // NOT: re-authing on those turns a rate-limited portal into a stampede — every call becomes
        // request + handshake + retry — which is exactly how we got a live portal to block us. Those
        // throws propagate; callers' runCatching degrades to empty.
        val js = try {
            rawRequest(params).jsOrNull()
        } catch (e: StalkerAuthException) {
            null   // fall through to the single re-auth + retry below
        }
        if (js != null) return@withContext js

        // Stale token (empty `js` / "Authorization failed." / 401 / 403) -> one handshake, retry once.
        Log.d(TAG, "Stalker request stale for ${account.name} (${params["action"]}) — re-authenticating")
        reauthenticate(staleToken)
        rawRequest(params).jsOrNull()
            ?: error("Stalker portal returned no data for ${params["action"]}")
    }

    /** Force re-auth on the next call (used when a create_link/browse hits a hard 401/403). */
    fun invalidate() { token = null }

    // --- Auth -----------------------------------------------------------------

    private suspend fun ensureAuthenticated() {
        if (token != null) return
        authMutex.withLock {
            if (token != null) return   // another coroutine authenticated while we waited
            doHandshakeAndProfile()
        }
    }

    /**
     * Re-handshake ONCE for a stale [staleToken]. Single-flight like [ensureAuthenticated]: if another
     * coroutine already refreshed the token while we waited on the lock, reuse theirs instead of
     * handshaking again. Critical because a Stalker handshake OVERWRITES the MAC's token server-side —
     * N concurrent browse calls all re-authing would rotate the token N times and invalidate each
     * other's retry ("portal error" on the return-to-app path).
     */
    private suspend fun reauthenticate(staleToken: String?) {
        authMutex.withLock {
            if (token != staleToken) return   // someone already refreshed — reuse it
            token = null
            doHandshakeAndProfile()
        }
    }

    /** Probe endpoints (if not resolved), handshake for a token, then get_profile to activate. */
    private suspend fun doHandshakeAndProfile() {
        val endpoint = resolvedEndpoint ?: probeEndpoint().also { resolvedEndpoint = it }
        val handshakeJs = rawRequestAt(
            endpoint,
            mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"),
            tokenOverride = ""
        ).jsOrNull() ?: error("Stalker handshake failed for ${account.name}")
        val newToken = handshakeJs.asJsonObject.get("token")?.asStringOrNull()
            ?: error("Stalker handshake returned no token for ${account.name}")
        token = newToken

        // get_profile activates the session. Non-fatal if it errors (some portals authorise on
        // handshake alone); we keep the token either way.
        runCatching {
            val profileParams = buildMap {
                put("type", "stb"); put("action", "get_profile"); put("hd", "1")
                put("ver", STB_VER)
                put("num_banks", "2"); put("stb_type", "MAG250"); put("client_type", "STB")
                put("image_version", "218"); put("video_out", "hdmi")
                put("hw_version", "1.7-BD-00"); put("not_valid_token", "0")
                put("device_id", identity.deviceId); put("device_id2", identity.deviceId2)
                if (account.sendDeviceId) put("signature", identity.signature)
                put("sn", identity.serialNumber)
                put("auth_second_step", "0"); put("prehash", "0")
                account.stalkerUsername.takeIf { it.isNotBlank() }?.let { put("login", it) }
                account.stalkerPassword.takeIf { it.isNotBlank() }?.let { put("password", it) }
            }
            rawRequestAt(endpoint, profileParams)
        }.onFailure { Log.d(TAG, "get_profile non-fatal failure for ${account.name}", it) }
    }

    /** Try each candidate endpoint until one handshakes with a token. Throws if none do. */
    private suspend fun probeEndpoint(): String {
        var lastError: Throwable? = null
        for (candidate in StalkerProtocol.ENDPOINT_CANDIDATES) {
            val ok = runCatching {
                rawRequestAt(
                    candidate,
                    mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"),
                    tokenOverride = ""
                ).jsOrNull()?.asJsonObject?.get("token")?.asStringOrNull()?.isNotBlank() == true
            }.onFailure { lastError = it }.getOrDefault(false)
            if (ok) {
                Log.d(TAG, "Stalker endpoint resolved for ${account.name}: $candidate")
                return candidate
            }
        }
        throw (lastError ?: IllegalStateException("No Stalker endpoint responded for ${account.name}"))
    }

    // --- HTTP -----------------------------------------------------------------

    private suspend fun rawRequest(params: Map<String, String>): JsonElement =
        rawRequestAt(resolvedEndpoint ?: StalkerProtocol.ENDPOINT_CANDIDATES.first(), params)

    /** One raw GET to [endpointPath] with full MAG headers. [tokenOverride] "" = the handshake call
     *  (no bearer yet); null = use the current session token. */
    private suspend fun rawRequestAt(
        endpointPath: String,
        params: Map<String, String>,
        tokenOverride: String? = null
    ): JsonElement {
        val urlBuilder = ("$baseUrl$endpointPath").toHttpUrlOrNull()
            ?.newBuilder() ?: error("Invalid Stalker portal URL: $baseUrl")
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        urlBuilder.addQueryParameter("JsHttpRequest", "1-xml")

        val bearer = tokenOverride ?: token
        val cookie = buildString {
            append("mac=").append(StalkerProtocol.encodeMacForCookie(account.macAddress))
            append("; stb_lang=en; timezone=Europe/London")
            append("; sn=").append(identity.serialNumber)
            append("; PHPSESSID=null")
        }
        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .header("X-User-Agent", X_USER_AGENT)
            .header("Referer", referer)
            .header("Cookie", cookie)
            .header("Accept", "*/*")
        if (!bearer.isNullOrEmpty()) builder.header("Authorization", "Bearer $bearer")

        // The gate is the backstop against UI fan-out (the hub fires one get_short_epg per channel
        // tile as it composes). Nothing reaches the portal outside it.
        return gate.withPermit { http.newCall(builder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) {
                // Signal a stale token to the retry path by returning an empty envelope.
                return@use JsonObject()
            }
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            // A portal that rejects the STB identity replies HTTP 200 with the plain text
            // "Authorization failed." (not JSON). A stale token recovers via re-auth; a persistent
            // rejection would otherwise surface as a vague "no data". Throw an actionable error — it
            // only becomes terminal when re-auth can't fix it (MAC/Serial/Device ID genuinely wrong).
            if (bodyStr.contains(AUTH_FAILED_MARKER, ignoreCase = true))
                throw StalkerAuthException("Stalker portal rejected this device for ${account.name} — check the MAC address (and Serial / Device ID if the portal requires them)")
            runCatching { JsonParser.parseString(bodyStr) }.getOrDefault(JsonObject())
        } }
    }

    // --- JSON helpers ---------------------------------------------------------

    /** The `js` element of a `{"js": …}` envelope, or null if absent/empty/false. */
    private fun JsonElement.jsOrNull(): JsonElement? {
        val obj = this as? JsonObject ?: return null
        val js = obj.get("js") ?: return null
        return when {
            js.isJsonNull -> null
            js.isJsonPrimitive && js.asJsonPrimitive.isBoolean && !js.asBoolean -> null
            js.isJsonObject && js.asJsonObject.size() == 0 -> null
            js.isJsonArray && js.asJsonArray.size() == 0 -> js   // an empty list IS valid data (no channels)
            else -> js
        }
    }

    private fun JsonElement.asStringOrNull(): String? =
        runCatching { if (isJsonNull) null else asString }.getOrNull()

    companion object {
        private const val TAG = "StalkerSession"
        // The reference server's rejection sentinel: `echo 'Authorization failed.'; exit;`
        private const val AUTH_FAILED_MARKER = "Authorization failed"
        // ponytail: fixed ceiling, no adaptive backoff. Raise only with evidence a portal tolerates
        // more; add backoff only if we start seeing 429s at this level.
        private const val MAX_CONCURRENT_REQUESTS = 4
        private const val STB_VER =
            "ImageDescription: 0.2.18-r14-pub-250; ImageDate: Wed Aug 29 10:49:52 EEST 2018; PORTAL version: 5.6.1; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"
        private const val USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        private const val X_USER_AGENT = "Model: MAG250; Link: WiFi"
    }
}
