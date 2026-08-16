package com.nuvio.tv.core.iptv.stalker

import android.util.Log
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nuvio.tv.core.iptv.IptvPanelGuard
import com.nuvio.tv.core.iptv.PanelHostGuard
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.guardedPanelRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
 * get_profile answered `status: 1`: the portal understood our identity and REFUSED the account
 * (disabled line, unknown MAC, …). Never retried — re-handshaking cannot fix a refusal, and a
 * status refusal must never be mistaken for an empty portal.
 */
open class StalkerPortalRefusedException(message: String) : IllegalStateException(message)

/**
 * The `status: 1` refusal names the DEVICE BINDING: the portal has a different device identity
 * pinned to this MAC. The one refusal with a user remedy, so it gets its own type and message.
 */
class StalkerDeviceConflictException(message: String) : StalkerPortalRefusedException(message)

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
    private val http: OkHttpClient,
    // The per-origin circuit breaker every portal request is admitted through (WP6). Injectable so
    // tests drive it with their own clock; production shares the process-wide instance.
    private val panelGuard: PanelHostGuard = IptvPanelGuard.guard
) {
    @Volatile private var token: String? = null
    /** When a re-auth ran and the retry STILL came back empty (another device holds the MAC). */
    @Volatile private var lastFailedReauthAtMs: Long = 0L
    @Volatile private var resolvedEndpoint: String? = null   // e.g. "/portal.php"
    /**
     * The STB identity this portal accepted. Starts at the one we have always sent, so a portal that
     * already works is unaffected; a rejection walks [StalkerMagPresets.LADDER]. Session-scoped: a
     * relaunch re-walks it, which costs one rejected request on the minority of portals that need it.
     */
    @Volatile private var magPreset: StalkerMagPreset = StalkerMagPresets.DEFAULT

    private val authMutex = Mutex()

    // Hard ceiling on concurrent requests to this portal. A real MAG box opens a couple of
    // connections; magplex (the reference client) caps this at 3 explicitly "to prevent rate
    // limiting". Ours is per-session so a busy UI can't fan out into a ban.
    private val gate = Semaphore(MAX_CONCURRENT_REQUESTS)

    // The get_events keep-alive (see [StalkerWatchdogPolicy]). Session-owned so its lifetime IS
    // the session's: (re)started on every successful activation, gone on [shutdown] (the manager
    // shuts a session down when it evicts/replaces it).
    private val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null

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
        if (js != null) {
            lastFailedReauthAtMs = 0L   // healthy again
            return@withContext js
        }

        // Stale token (empty body / empty `js` / "Authorization failed." / 401 / 403) -> one
        // handshake, retry once. Cooldown first: when two devices share a MAC (a phone and this TV
        // on one Stalker line) each handshake evicts the other, so a re-auth that failed to recover
        // must NOT have every following request handshake again — that pair would spin forever and
        // the request storm is what gets a portal to ban the IP.
        val now = System.currentTimeMillis()
        if (lastFailedReauthAtMs != 0L && now - lastFailedReauthAtMs < REAUTH_COOLDOWN_MS) {
            error("Stalker session for ${account.name} is held by another device — cooling down")
        }
        Log.d(TAG, "Stalker request stale for ${account.name} (${params["action"]}) — re-authenticating")
        reauthenticate(staleToken)
        val retried = rawRequest(params).jsOrNull()
        if (retried == null) {
            lastFailedReauthAtMs = now
            error("Stalker portal returned no data for ${params["action"]} — the session is in use elsewhere")
        }
        lastFailedReauthAtMs = 0L
        retried
    }

    /** Force re-auth on the next call (used when a create_link/browse hits a hard 401/403). */
    fun invalidate() { token = null }

    /** Tear the session down (evicted/replaced): stops the watchdog. Do not use it afterwards. */
    fun shutdown() {
        watchdogScope.cancel()
    }

    /**
     * Authenticated GET that STREAMS the body to [onChunk] instead of materializing it — for the
     * one Stalker response that can be enormous (bulk `get_epg_info`: 174.5 MB in a real client
     * trace against our research mock; research/iptv-catalog-loading.md). Same auth + retry
     * DISCIPLINE as [request] but split across layers: this is ONE attempt (it ensures auth,
     * sniffs the first bytes for the "Authorization failed." sentinel / empty body, throws
     * [StalkerAuthException] on either), and the caller owns the single re-auth retry — its
     * consumer has to reset per attempt anyway (the EPG ingest re-opens its transaction).
     * Returns true when body bytes arrived. Never re-handshakes on its own.
     */
    suspend fun requestStreamOnce(
        params: Map<String, String>,
        onChunk: (String) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        ensureAuthenticated()
        val endpointPath = resolvedEndpoint ?: StalkerProtocol.ENDPOINT_CANDIDATES.first()
        val urlBuilder = ("$baseUrl$endpointPath").toHttpUrlOrNull()
            ?.newBuilder() ?: error("Invalid Stalker portal URL: $baseUrl")
        params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
        urlBuilder.addQueryParameter("JsHttpRequest", "1-xml")
        val cookie = buildString {
            append("mac=").append(StalkerProtocol.encodeMacForCookie(account.macAddress))
            append("; stb_lang=en; timezone=Europe/London")
            append("; sn=").append(identity.serialNumber)
            append("; PHPSESSID=null")
        }
        val builder = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", magPreset.userAgent)
            .header("X-User-Agent", magPreset.xUserAgent)
            .header("Referer", referer)
            .header("Cookie", cookie)
            .header("Accept", "*/*")
        token?.takeIf { it.isNotEmpty() }?.let { builder.header("Authorization", "Bearer $it") }

        // Guarded like rawRequestAt (WP6): the bulk-EPG stream is a panel request too. A sniffed
        // rejection sentinel throws from inside the block, which classifies as HTTP_RESPONSE —
        // body bytes arrived, so the host is alive and the record clears.
        panelGuard.guardedPanelRequest(urlBuilder.build().toString()) {
        gate.withPermit {
            http.newCall(builder.build()).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw StalkerAuthException("Stalker portal answered ${resp.code} for ${account.name}")
                }
                if (!resp.isSuccessful) error("Stalker portal answered HTTP ${resp.code}")
                val source = resp.body?.source() ?: return@use false
                var sniffing = true
                val sniff = StringBuilder()
                var sawBytes = false
                val buf = ByteArray(STREAM_READ_BYTES)
                var carry = ByteArray(0)   // incomplete trailing UTF-8 sequence from the last read
                while (true) {
                    val n = source.read(buf, carry.size, buf.size - carry.size)
                    if (n == -1) break
                    if (n == 0) continue
                    sawBytes = true
                    carry.copyInto(buf, 0)
                    val total = carry.size + n
                    // Hold back a trailing incomplete multi-byte sequence so a chunk boundary can't
                    // split a character (which would land replacement chars in programme titles).
                    var cut = total
                    var back = 0
                    while (back < 3 && cut > 0) {
                        val b = buf[cut - 1].toInt() and 0xFF
                        if (b < 0x80) break                       // ASCII tail — complete
                        if (b >= 0xC0) {                          // lead byte: is its sequence complete?
                            val need = when {
                                b >= 0xF0 -> 4; b >= 0xE0 -> 3; else -> 2
                            }
                            if (total - (cut - 1) < need) cut -= 1 // incomplete — hold it back
                            break
                        }
                        cut -= 1; back += 1                       // continuation byte — keep walking
                    }
                    carry = buf.copyOfRange(cut, total)
                    if (cut == 0) continue
                    val chunk = String(buf, 0, cut, Charsets.UTF_8)
                    if (sniffing) {
                        sniff.append(chunk)
                        if (sniff.contains(AUTH_FAILED_MARKER, ignoreCase = true)) {
                            throw StalkerAuthException(
                                "Stalker portal rejected this device for ${account.name} — check the MAC address (and Serial / Device ID if the portal requires them)"
                            )
                        }
                        if (sniff.length > SNIFF_WINDOW) {
                            sniffing = false
                            onChunk(sniff.toString())
                            sniff.clear()
                        }
                    } else {
                        onChunk(chunk)
                    }
                }
                if (sniffing && sniff.isNotEmpty()) onChunk(sniff.toString())
                sawBytes
            }
        }
        }
    }

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
        //
        // The ONE failure we must not shrug off is an identity rejection: a portal provisioned for a
        // different box answers the plain text "Authorization failed." here, and every later content
        // call then returns nothing. Left swallowed, that reads as an empty portal. Catch it, take
        // the next rung of the identity ladder and re-handshake — the token is bound to the identity
        // that requested it, so the whole bootstrap has to be redone, not just the profile call.
        val profileOutcome = runCatching {
            rawRequestAt(endpoint, profileParams(authSecondStep = false))
        }.onFailure { Log.d(TAG, "get_profile non-fatal failure for ${account.name}", it) }

        val rejection = profileOutcome.exceptionOrNull() as? StalkerAuthException
        if (rejection == null) {
            val profileJs = profileOutcome.getOrNull()?.jsOrNull() as? JsonObject
            // status=1 is a REFUSAL (the portal understood us and said no) — never "empty portal".
            throwIfRefused(profileJs)
            val activatedJs = runFollowUpBootstrap(endpoint, profileJs)
            startWatchdog(activatedJs ?: profileJs)
            return
        }
        val nextPreset = StalkerMagPresets.next(magPreset)
        if (nextPreset == null) {
            // Every identity we know was refused. The MAC/serial genuinely is not provisioned here,
            // which is what the exception already says — surface it instead of looping.
            Log.w(TAG, "Stalker identity ladder exhausted for ${account.name}")
            throw rejection
        }
        Log.w(
            TAG,
            "Stalker portal rejected identity ${magPreset.id} for ${account.name}; retrying as ${nextPreset.id}"
        )
        magPreset = nextPreset
        token = null
        doHandshakeAndProfile()
    }

    /** The full MAG profile params. [authSecondStep] is set ONLY by the post-do_auth retry —
     *  the portal's own client sends get_user_profile(false) at boot and (true) after do_auth. */
    private fun profileParams(authSecondStep: Boolean): Map<String, String> = buildMap {
        put("type", "stb"); put("action", "get_profile"); put("hd", "1")
        put("ver", magPreset.stbVer)
        put("num_banks", "2"); put("stb_type", magPreset.stbType); put("client_type", "STB")
        put("image_version", magPreset.imageVersion); put("video_out", "hdmi")
        put("hw_version", magPreset.hwVersion); put("not_valid_token", "0")
        put("device_id", identity.deviceId); put("device_id2", identity.deviceId2)
        if (account.sendDeviceId) put("signature", identity.signature)
        put("sn", identity.serialNumber)
        put("auth_second_step", if (authSecondStep) "1" else "0"); put("prehash", "0")
        account.stalkerUsername.takeIf { it.isNotBlank() }?.let { put("login", it) }
        account.stalkerPassword.takeIf { it.isNotBlank() }?.let { put("password", it) }
    }

    /** Throws the typed refusal for a `status: 1` profile — see [StalkerBootstrapPolicy.refusalAfterProfile]. */
    private fun throwIfRefused(profileJs: JsonObject?) {
        val refusal = StalkerBootstrapPolicy.refusalAfterProfile(
            status = profileJs?.get("status")?.asIntOrNull(),
            msg = profileJs?.get("msg")?.asStringOrNull(),
            blockMsg = profileJs?.get("block_msg")?.asStringOrNull(),
        ) ?: return
        val portalSaid = refusal.portalText?.let { " Portal says: $it" }.orEmpty()
        if (refusal.deviceConflict) {
            throw StalkerDeviceConflictException(
                "Another device is using this MAC on ${account.name} — the portal has a different " +
                    "Device ID pinned to it. Stop the other device or ask the provider to reset the MAC.$portalSaid"
            )
        }
        throw StalkerPortalRefusedException(
            "Stalker portal refused ${account.name}." +
                portalSaid.ifEmpty { " The account may be disabled or the MAC not provisioned." }
        )
    }

    /**
     * The extra calls some portals require before they will serve anything — see
     * [StalkerBootstrapPolicy]. Best-effort: a portal that did not want these answers them with
     * junk, and failing the whole session over an optional step would be worse than not asking.
     *
     * Returns the auth_second_step profile's js when that retry ran (its fields are the freshest —
     * the watchdog cadence should come from it), else null.
     */
    private suspend fun runFollowUpBootstrap(endpoint: String, js: JsonObject?): JsonObject? {
        if (js == null) return null
        val steps = StalkerBootstrapPolicy.stepsAfterProfile(
            authAccess = js.get("auth_access")?.asBooleanOrNull(),
            status = js.get("status")?.asIntOrNull(),
            hasCredentials = account.stalkerUsername.isNotBlank() && account.stalkerPassword.isNotBlank(),
        )
        var secondStepJs: JsonObject? = null
        for (step in steps) {
            when (step) {
                StalkerBootstrapPolicy.Step.DO_AUTH -> {
                    val authed = runCatching {
                        rawRequestAt(
                            endpoint,
                            mapOf(
                                "type" to "stb",
                                "action" to "do_auth",
                                "login" to account.stalkerUsername,
                                "password" to account.stalkerPassword,
                            )
                        ).jsOrNull()
                    }.onFailure { Log.d(TAG, "Stalker do_auth failed for ${account.name}", it) }
                        .getOrNull() != null
                    // The portal's own client re-fetches the profile with auth_second_step=1 after
                    // a successful do_auth (c/xpcom.common.js) — ONLY that retry sets the flag.
                    if (authed) {
                        secondStepJs = runCatching {
                            rawRequestAt(endpoint, profileParams(authSecondStep = true))
                        }.onFailure { Log.d(TAG, "second-step get_profile failed for ${account.name}", it) }
                            .getOrNull()?.jsOrNull() as? JsonObject
                        // A refusal on the retry is as final as one on the first profile.
                        throwIfRefused(secondStepJs)
                    }
                }
                StalkerBootstrapPolicy.Step.GET_MODULES -> {
                    runCatching { rawRequestAt(endpoint, mapOf("type" to "stb", "action" to "get_modules")) }
                        .onFailure { Log.d(TAG, "Stalker get_modules failed for ${account.name}", it) }
                }
            }
        }
        return secondStepJs
    }

    /**
     * (Re)start the get_events keep-alive with the cadence [profileJs] advertises. The init ping
     * rides activation INLINE (a real box pings before it browses; strict portals read it as part
     * of the bootstrap) — but its failure never fails auth, and the periodic loop pings only while
     * a token exists: the keep-alive must NEVER re-handshake on its own, because a handshake
     * evicts the other device on a shared MAC. Ping failures are log-only by contract — a missed
     * ping only affects the portal's "online" reporting.
     */
    private suspend fun startWatchdog(profileJs: JsonObject?) {
        val timing = StalkerWatchdogPolicy.timingFrom(
            watchdogTimeoutSeconds = profileJs?.get("watchdog_timeout")?.asStringOrNull()?.trim()?.toDoubleOrNull()?.toLong(),
            timeslotSeconds = profileJs?.get("timeslot")?.asStringOrNull()?.trim()?.toDoubleOrNull(),
        )
        runCatching { rawRequest(StalkerWatchdogPolicy.pingParams(init = true)) }
            .onFailure { Log.d(TAG, "watchdog init ping failed for ${account.name}", it) }
        watchdogJob?.cancel()
        watchdogJob = watchdogScope.launch {
            delay(StalkerWatchdogPolicy.initialPeriodicDelayMs(timing))
            while (isActive) {
                if (token != null) {
                    runCatching { rawRequest(StalkerWatchdogPolicy.pingParams(init = false)) }
                        .onFailure { Log.d(TAG, "watchdog ping failed for ${account.name}: ${it.message}") }
                }
                delay(StalkerWatchdogPolicy.periodMs(timing))
            }
        }
    }

    /** Try each candidate endpoint until one handshakes with a token. Throws if none do.
     *  The probes carry the guard's discovery flag (WP6): they run even while the breaker is open
     *  and their failures are never counted — but a probe that reaches the host clears its record. */
    private suspend fun probeEndpoint(): String {
        var lastError: Throwable? = null
        for (candidate in StalkerProtocol.ENDPOINT_CANDIDATES) {
            val ok = runCatching {
                rawRequestAt(
                    candidate,
                    mapOf("type" to "stb", "action" to "handshake", "token" to "", "prehash" to "0"),
                    tokenOverride = "",
                    discovery = true
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

    /**
     * Hold browse traffic back while a stream from this portal is playing — most Stalker accounts
     * allow barely any concurrent connections, and a guide pulling categories can cost the viewer
     * the picture. Bootstrap and link creation are exempt: playback depends on them.
     */
    private suspend fun awaitPlaybackTraffic(action: String) {
        val isExempt = action in PLAYBACK_CRITICAL_ACTIONS
        var waited = 0L
        while (
            StalkerPlaybackTraffic.shouldDefer(
                playbackActive = StalkerPlaybackTraffic.isPlaybackActive,
                waitedMs = waited,
                isBootstrap = isExempt
            )
        ) {
            delay(StalkerPlaybackTraffic.DEFER_SLICE_MS)
            waited += StalkerPlaybackTraffic.DEFER_SLICE_MS
        }
    }

    private suspend fun rawRequest(params: Map<String, String>): JsonElement =
        rawRequestAt(resolvedEndpoint ?: StalkerProtocol.ENDPOINT_CANDIDATES.first(), params)

    /** One raw GET to [endpointPath] with full MAG headers. [tokenOverride] "" = the handshake call
     *  (no bearer yet); null = use the current session token. */
    private suspend fun rawRequestAt(
        endpointPath: String,
        params: Map<String, String>,
        tokenOverride: String? = null,
        // Endpoint-discovery probe (WP6): admitted even while the breaker is open, its failures
        // never counted — discovery expects most candidates to fail. Successes still clear.
        discovery: Boolean = false
    ): JsonElement {
        val action = params["action"].orEmpty()
        // Captured at ENQUEUE: if the user switches providers while this call waits for a gate
        // permit, it is answering a screen nobody is on — drop it instead of spending the
        // throttled host's budget on it (see StalkerPlaybackTraffic.browseEpoch).
        val enqueueEpoch = StalkerPlaybackTraffic.browseEpoch
        awaitPlaybackTraffic(action)
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
            .header("User-Agent", magPreset.userAgent)
            .header("X-User-Agent", magPreset.xUserAgent)
            .header("Referer", referer)
            .header("Cookie", cookie)
            .header("Accept", "*/*")
        if (!bearer.isNullOrEmpty()) builder.header("Authorization", "Bearer $bearer")

        // The gate is the backstop against UI fan-out (the hub fires one get_short_epg per channel
        // tile as it composes). Nothing reaches the portal outside it.
        // The panel guard sits OUTSIDE the gate (WP6): a fast-fail must not queue behind requests
        // that are busy timing out, and its refusal (PanelHostFastFailException — never an auth
        // type, and worded to read as a connection-level failure) propagates without re-auth.
        // A body-read failure after the status line classifies as a reset (inconclusive); the
        // rejection sentinel / HTTP-status throws classify as HTTP_RESPONSE — the host answered.
        return panelGuard.guardedPanelRequest(urlBuilder.build().toString(), discovery) {
            gate.withPermit {
                // Checked with the permit in hand — the whole wait is the window a switch can
                // land in. Thrown INSIDE the guard, which classifies it as neutral (an abandoned
                // call is not a panel failure); callers treat it like any transport failure.
                if (StalkerPlaybackTraffic.isAbandoned(
                        requestEpoch = enqueueEpoch,
                        currentEpoch = StalkerPlaybackTraffic.browseEpoch,
                        isCritical = action in PLAYBACK_CRITICAL_ACTIONS,
                    )
                ) {
                    throw StalkerBrowseAbandonedException()
                }
                http.newCall(builder.build()).execute().use { resp ->
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

    /**
     * Portals are loose about types — `auth_access` and `status` arrive as booleans, numbers or
     * quoted strings depending on the panel. Null means the field was absent or unreadable, which
     * [StalkerBootstrapPolicy] treats as "nothing further needed" rather than guessing.
     */
    private fun JsonElement.asBooleanOrNull(): Boolean? = runCatching {
        when {
            isJsonNull -> null
            asJsonPrimitive.isBoolean -> asBoolean
            asJsonPrimitive.isNumber -> asInt != 0
            else -> when (asString.trim().lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> null
            }
        }
    }.getOrNull()

    private fun JsonElement.asIntOrNull(): Int? = runCatching {
        if (isJsonNull) null else asString.trim().toIntOrNull()
    }.getOrNull()

    companion object {
        private const val TAG = "StalkerSession"
        // The reference server's rejection sentinel: `echo 'Authorization failed.'; exit;`
        private const val AUTH_FAILED_MARKER = "Authorization failed"
        /**
         * How long to stop re-handshaking after a re-auth failed to recover. Two devices sharing a
         * MAC evict each other on every handshake, so without this each request would handshake
         * again and the pair would spin — a self-inflicted request storm, and portals ban for that.
         */
        private const val REAUTH_COOLDOWN_MS = 30_000L
        /** Bytes buffered at stream start to sniff "Authorization failed." before forwarding. */
        private const val SNIFF_WINDOW = 512
        /** Streaming read size — the whole point is that nothing body-sized is ever held. */
        private const val STREAM_READ_BYTES = 16 * 1024
        // ponytail: fixed ceiling, no adaptive backoff. Raise only with evidence a portal tolerates
        // more; add backoff only if we start seeing 429s at this level.
        // Was 4; lowered after tracing TiviMate 5.3.3 against a controlled portal: the category
        // leader runs STRICTLY serial against Stalker portals (peak concurrency 1 across its whole
        // session, even the initial load), and a real portal's Cloudflare has banned this app
        // before over request volume. 2 keeps browse+EPG overlap without looking like a scraper.
        // (research/iptv-catalog-loading.md)
        private const val MAX_CONCURRENT_REQUESTS = 2

        /** Never held back by [StalkerPlaybackTraffic] — playback itself depends on these.
         *  get_events is here because the init ping rides the auth path (deferring it would add
         *  its wait to every mid-playback zap) and the keep-alive is a few bytes every ~120s. */
        private val PLAYBACK_CRITICAL_ACTIONS = setOf(
            "handshake", "get_profile", "create_link", "do_auth", "get_modules", "get_main_info",
            "get_events"
        )
    }
}
