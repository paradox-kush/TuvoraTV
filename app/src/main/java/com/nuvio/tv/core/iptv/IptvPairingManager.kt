package com.nuvio.tv.core.iptv

import android.util.Log
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.data.remote.supabase.IptvPairingPollResult
import com.nuvio.tv.data.remote.supabase.IptvPairingStartResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "IptvPairingManager"

/**
 * P5 QR/web playlist pairing for a NOT-signed-in NuvioTV. Mirrors [com.nuvio.tv.core.auth.AuthManager]'s
 * tv-login RPC plumbing (the device-nonce/hash + `postgrest.rpc` pattern) but against the anon-callable
 * `create_iptv_pairing` / `poll_iptv_pairing` RPCs so it works with NO Supabase session — the pairing
 * happens before/without an account. The web form (phone) is the one that calls `submit_iptv_pairing`,
 * so there is no submit method here.
 *
 * Security: a fresh random device secret is generated per session and NEVER leaves the TV; only its
 * sha256 hex ([sha256Hex]) is sent as `p_code_hash`. The backend hashes that again before storing/
 * comparing, so a scanned/overheard code alone can't claim the payload on another device.
 */
@Singleton
class IptvPairingManager @Inject constructor(
    private val supabaseProvider: SyncBackendSupabaseProvider,
) {
    private val postgrest
        get() = supabaseProvider.postgrest

    /** The just-created session: the human [code], the [secret] (kept only in memory, for polling),
     *  the [webUrl] to encode in the QR, and when it [expiresAtIso] + the server [pollIntervalSeconds]. */
    data class Pairing(
        val code: String,
        val secret: String,
        val webUrl: String,
        val expiresAtIso: String?,
        val pollIntervalSeconds: Int
    )

    /**
     * Creates a pairing session: generates a 6-char code + a random device secret, sends the
     * secret's hash to `create_iptv_pairing`, and returns everything the screen needs (incl. the
     * `?code=` web URL to render as a QR). Anon-safe: no session required.
     */
    suspend fun createPairing(webBaseUrl: String): Result<Pairing> {
        return try {
            val code = generatePairingCode()
            val secret = generatePairingSecret()
            val params = buildJsonObject {
                put("p_code", code)
                put("p_code_hash", sha256Hex(secret))
            }
            val response = postgrest.rpc("create_iptv_pairing", params)
            val result = response.decodeList<IptvPairingStartResult>().firstOrNull()
                ?: return Result.failure(IllegalStateException("Empty response from create_iptv_pairing"))
            Result.success(
                Pairing(
                    // Trust the server-normalised code (upper/trim), falling back to ours.
                    code = result.code.ifBlank { code },
                    secret = secret,
                    webUrl = pairingWebUrl(webBaseUrl, result.code.ifBlank { code }),
                    expiresAtIso = result.expiresAt,
                    pollIntervalSeconds = result.pollIntervalSeconds.coerceAtLeast(2)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create IPTV pairing session", e)
            Result.failure(e)
        }
    }

    /**
     * One poll of `poll_iptv_pairing`. Returns the status ("pending" | "consumed" | "expired") and,
     * when consumed, the submitted payload exactly once. The device [secret] (not its hash) is passed
     * — this manager hashes it here, matching how the create call hashed it. A wrong secret raises on
     * the server; that surfaces as a failed Result (the screen treats it as an error, not a status).
     */
    suspend fun pollOnce(code: String, secret: String): Result<IptvPairingPollResult> {
        return try {
            val params = buildJsonObject {
                put("p_code", code)
                put("p_code_hash", sha256Hex(secret))
            }
            val response = postgrest.rpc("poll_iptv_pairing", params)
            val result = response.decodeList<IptvPairingPollResult>().firstOrNull()
                ?: return Result.failure(IllegalStateException("Empty response from poll_iptv_pairing"))
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to poll IPTV pairing session", e)
            Result.failure(e)
        }
    }
}
