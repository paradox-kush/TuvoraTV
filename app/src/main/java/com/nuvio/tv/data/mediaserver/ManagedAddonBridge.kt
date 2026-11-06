package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.build.AppFeaturePolicy
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.core.network.SyncBackendDefaults
import com.nuvio.tv.core.network.hasSameConnectionIdentity
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.SyncClientIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

@Singleton
class ManagedAddonBridge @Inject constructor(
    private val provider: SyncBackendSupabaseProvider,
    private val auth: AuthManager,
    private val profiles: ProfileManager,
    private val identity: SyncClientIdentity,
    private val repository: ServerRepository
) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = kotlinx.coroutines.sync.Mutex()
    init { repository.managedSessionRefresher = ::refresh }
    suspend fun refresh(profileId: Int, force: Boolean = false): Unit = mutex.withLock { refreshLocked(profileId, force) }

    private suspend fun refreshLocked(profileId: Int, force: Boolean): Unit = withContext(Dispatchers.IO) {
        if (AppFeaturePolicy.addonStreamSourcesEnabled || !SyncBackendDefaults.hosted().hasSameConnectionIdentity(provider.selectedBackend)) return@withContext
        val bearer = auth.currentAccessToken() ?: return@withContext
        fun current() = profiles.activeProfileId.value == profileId && auth.currentAccessToken() == bearer
        try {
            val status = provider.postgrest.rpc("addon_bridge_status", buildJsonObject { put("p_profile_id", profileId) })
                .decodeAs<kotlinx.serialization.json.JsonElement>() as? JsonObject
            if (!current()) return@withContext
            repository.selectProfile(profileId)
            when (val decision = ManagedBridgePolicy.decide(status)) {
                ManagedBridgeDecision.Forget -> { repository.setManagedNotice(null); repository.forgetManaged(); return@withContext }
                // Rebuilding or failed on the bridge: keep the user's connection and say why, never silently delete it.
                ManagedBridgeDecision.Preparing -> { repository.setManagedNotice(ManagedBridgeNotice.Preparing); return@withContext }
                is ManagedBridgeDecision.Failed -> { repository.setManagedNotice(ManagedBridgeNotice.Failed(decision.code)); return@withContext }
                ManagedBridgeDecision.Ready -> Unit
            }
            if (status == null) return@withContext
            val id = status["id"]?.jsonPrimitive?.content ?: return@withContext
            if (!ManagedBridgePolicy.validId(id)) return@withContext
            val revision = status["revision"]!!.jsonPrimitive.content.toLong()
            val details = status["result"] as? JsonObject ?: return@withContext
            val sourceRef = "jellyfin|${details["server_id"]?.jsonPrimitive?.content}|${details["user_id"]?.jsonPrimitive?.content}"
            if (!com.nuvio.tv.core.sources.DeviceSources.allows("media_server", sourceRef)) return@withContext
            repository.forgetManaged(exceptId = id)
            val existing = repository.uiState.value.connections.firstOrNull { it.managedBridgeId == id }
            if (existing != null && !existing.enabled) return@withContext
            if (!force && status["needs_check"]?.jsonPrimitive?.content != "true" && existing != null && existing.managedRevision == revision && repository.session(existing.id) != null &&
                existing.managedExpiresAtMs > System.currentTimeMillis() + 30 * 60_000) return@withContext
            val body = buildJsonObject { put("action", "exchange"); put("device_id", identity.currentClientId()); put("force", force) }.toString()
            val request = Request.Builder().url("https://addon.thekush.dev/api/bridge?profile=$profileId")
                .header("Authorization", "Bearer $bearer").post(body.toRequestBody("application/json".toMediaType())).build()
            val responseBody = http.newCall(request).execute().use { response ->
                val source = response.body?.source() ?: return@withContext
                if (source.request(32769)) return@withContext
                val text = source.readUtf8()
                if (!response.isSuccessful) {
                    repository.setManagedNotice(ManagedBridgePolicy.exchangeFailure(response.code, text))
                    return@withContext
                }
                text
            }
            if (!current()) return@withContext
            val data = json.parseToJsonElement(responseBody) as? JsonObject ?: return@withContext
            fun field(name: String) = data[name]?.jsonPrimitive?.content.orEmpty()
            if (field("id") != id || !ManagedBridgePolicy.validAddress(id, field("address"))) return@withContext
            repository.setManagedNotice(null)
            repository.connectManaged(ServerSignIn(field("address"), "Connected addons", field("server_id"), field("user_id"), field("user_name"), field("access_token")),
                id, Instant.parse(field("expires_at")).toEpochMilli(), revision)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            android.util.Log.w("ManagedAddonBridge", "Refresh unavailable: ${e.javaClass.simpleName}")
            repository.setManagedNotice(ManagedBridgeNotice.Failed("unreachable"))
        }
    }
}

internal sealed interface ManagedBridgeDecision {
    data object Ready : ManagedBridgeDecision
    data object Preparing : ManagedBridgeDecision
    data object Forget : ManagedBridgeDecision
    data class Failed(val code: String?) : ManagedBridgeDecision
}

sealed interface ManagedBridgeNotice {
    data object Preparing : ManagedBridgeNotice
    data class Failed(val code: String?) : ManagedBridgeNotice
}

internal object ManagedBridgePolicy {
    /** Only a missing or disconnected connection is forgotten; a pending, working or failed one is kept and explained. */
    fun decide(status: JsonObject?): ManagedBridgeDecision = when (status?.get("state")?.jsonPrimitive?.content) {
        "ready" -> ManagedBridgeDecision.Ready
        "pending", "working" -> ManagedBridgeDecision.Preparing
        "failed" -> ManagedBridgeDecision.Failed((status["result"] as? JsonObject)?.get("error")?.jsonPrimitive?.content)
        else -> ManagedBridgeDecision.Forget
    }

    fun exchangeFailure(status: Int, body: String): ManagedBridgeNotice {
        val code = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("error")?.jsonPrimitive?.content }.getOrNull()
        return when {
            code == "connection_reprovisioning" || code == "connection_not_ready" || code == "connection_changed" || status == 410 -> ManagedBridgeNotice.Preparing
            else -> ManagedBridgeNotice.Failed(code ?: "http_$status")
        }
    }

    fun validId(value: String): Boolean = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)
    fun validAddress(id: String, address: String): Boolean = validId(id) && address == "https://addon.thekush.dev/jellyfin/$id"
}
