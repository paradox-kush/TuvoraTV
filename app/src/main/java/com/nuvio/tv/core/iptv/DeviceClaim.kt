package com.nuvio.tv.core.iptv

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.core.sync.toXtreamAccountOrNull
import com.nuvio.tv.data.local.XtreamAccountStore
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.remote.supabase.SupabaseIptvPlaylist
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * B205 — a provider setup code redeemed with NO Tuvora account. The server spends the code and hands the playlists
 * to the device with a claim hash; the token behind that hash is the only way to move the setup into an account
 * later (`claim_device_setup`). See research/setup-code-signed-out-design.md.
 */

/** One playlist of a device redeem, in the wire spelling of `/api/s/redeem` (the same fields a pulled row has). */
@Serializable
data class DevicePlaylist(
    @SerialName("playlist_key") val playlistKey: String,
    val name: String? = null,
    @SerialName("source_type") val sourceType: String = "xtream",
    val action: String = "added",
    val reason: String? = null,
    @SerialName("base_url") val baseUrl: String? = null,
    val url: String? = null,
    @SerialName("portal_url") val portalUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    @SerialName("mac_address") val macAddress: String? = null,
    @SerialName("stalker_username") val stalkerUsername: String? = null,
    @SerialName("stalker_password") val stalkerPassword: String? = null,
    @SerialName("backup_urls") val backupUrls: List<String>? = null,
    @SerialName("epg_url") val epgUrl: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("content_types") val contentTypes: List<String>? = null,
) {
    val isAdded: Boolean get() = action == "added"

    /** The same mapping a pulled row goes through, so a device playlist is exactly what a sync would have made. */
    fun toAccountOrNull(): XtreamAccount? = SupabaseIptvPlaylist(
        playlistKey = playlistKey,
        backupUrls = backupUrls,
        sourceType = sourceType,
        name = name,
        baseUrl = baseUrl,
        username = username,
        password = password,
        epgUrl = epgUrl,
        contentTypes = contentTypes ?: listOf("live", "movies", "series"),
        url = url,
        userAgent = userAgent,
        portalUrl = portalUrl,
        macAddress = macAddress,
        stalkerUsername = stalkerUsername,
        stalkerPassword = stalkerPassword,
    ).toXtreamAccountOrNull()
}

/** The success body of `POST /api/s/redeem`. */
data class DeviceRedeem(val status: String, val provider: String?, val skippedAddons: Boolean, val playlists: List<DevicePlaylist>)

sealed interface DeviceRedeemOutcome {
    data class Done(val redeem: DeviceRedeem) : DeviceRedeemOutcome
    data class Failed(val outcome: SetupCodeOutcome) : DeviceRedeemOutcome
}

/** A spent device redeem waiting for an account. Never synced, never in analytics, never logged. */
@Serializable
data class DeviceClaim(
    val token: String,
    val providerName: String?,
    val createdAt: Long,
    val playlists: List<DevicePlaylist>,
) {
    /** Never prints the token or credentials. */
    override fun toString(): String = "DeviceClaim(playlists=${playlists.size})"
}

/** What `claim_device_setup` said. */
sealed interface ClaimOutcome {
    data object Claimed : ClaimOutcome
    data class Failed(val error: ClaimError) : ClaimOutcome
}

enum class ClaimError {
    NOT_FOUND, ALREADY_USED, REVOKED, SUSPENDED, UNAVAILABLE, RATE_LIMITED, PROFILE_NOT_FOUND, NETWORK, UNKNOWN;

    companion object {
        fun fromCode(code: String?): ClaimError = when (code?.trim()?.lowercase()) {
            "not_found" -> NOT_FOUND
            "already_used" -> ALREADY_USED
            "revoked" -> REVOKED
            "suspended" -> SUSPENDED
            "unavailable" -> UNAVAILABLE
            "rate_limited" -> RATE_LIMITED
            "profile_not_found" -> PROFILE_NOT_FOUND
            else -> UNKNOWN
        }
    }
}

/** Client token: 32 bytes from a secure random source as lowercase hex; the server stores only its SHA-256. */
object DeviceClaimToken {
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return hex(bytes)
    }

    fun hash(token: String): String = hex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

    private fun hex(b: ByteArray): String = buildString(b.size * 2) { b.forEach { append("%02x".format(it.toInt() and 0xff)) } }
}

/** The decision after a claim attempt, and which playlists a restore must re-add. Pure. */
object DeviceClaimPolicy {
    enum class Action { DROP_AND_PULL, RESTORE_AND_DROP, KEEP }

    fun decide(outcome: ClaimOutcome): Action = when (outcome) {
        ClaimOutcome.Claimed -> Action.DROP_AND_PULL
        is ClaimOutcome.Failed -> when (outcome.error) {
            ClaimError.NOT_FOUND, ClaimError.ALREADY_USED, ClaimError.REVOKED, ClaimError.SUSPENDED, ClaimError.UNAVAILABLE ->
                Action.RESTORE_AND_DROP
            ClaimError.RATE_LIMITED, ClaimError.NETWORK, ClaimError.PROFILE_NOT_FOUND, ClaimError.UNKNOWN -> Action.KEEP
        }
    }

    fun missing(claim: DeviceClaim, localKeys: Set<String>): List<DevicePlaylist> = missing(claim.playlists, localKeys)

    fun missing(playlists: List<DevicePlaylist>, localKeys: Set<String>): List<DevicePlaylist> =
        playlists.filter { it.isAdded && it.playlistKey !in localKeys }
}

/**
 * Pending claims in the app's own private preferences file. Deliberately NOT a profile-scoped DataStore:
 * sign-out and account switch wipe those (AccountLocalDataResetService), and a claim must outlive both.
 * Credentials are plaintext here exactly as in the playlist store; backups are off (allowBackup=false).
 */
@Singleton
class DeviceClaimStore internal constructor(private val prefs: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val serializer = ListSerializer(DeviceClaim.serializer())

    @Synchronized
    fun all(): List<DeviceClaim> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** Replaces a claim with the same token. */
    @Synchronized
    fun save(claim: DeviceClaim) {
        write(all().filterNot { it.token == claim.token } + claim)
    }

    @Synchronized
    fun remove(token: String) {
        write(all().filterNot { it.token == token })
    }

    private fun write(list: List<DeviceClaim>) {
        prefs.edit().putString(KEY, json.encodeToString(serializer, list)).commit()
    }

    companion object {
        const val FILE = "setup_code_device_claims"
        private const val KEY = "claims"
    }
}

/** Puts device-redeemed playlists into the active profile's local playlist store, never twice. */
@Singleton
class DeviceSetupPlaylists @Inject constructor(
    private val accountStore: XtreamAccountStore,
    private val profileManager: ProfileManager,
) {
    /** Adds each `added` playlist not already present (by playlist key, or by address). Returns how many were added. */
    suspend fun addMissing(playlists: List<DevicePlaylist>): Int {
        val existing = accountStore.accountsForProfile(profileManager.activeProfileId.value).toMutableList()
        var added = 0
        for (p in DeviceClaimPolicy.missing(playlists, existing.mapTo(mutableSetOf()) { it.id })) {
            val account = p.toAccountOrNull() ?: continue
            if (existing.any { it.id == account.id || PlaylistKey.sameAddress(it, account) }) continue
            accountStore.upsert(account)
            existing += account
            added++
        }
        return added
    }
}
