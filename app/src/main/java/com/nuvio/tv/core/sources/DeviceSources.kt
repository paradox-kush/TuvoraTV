package com.nuvio.tv.core.sources

import android.content.Context
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceSources @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: com.nuvio.tv.core.sync.SyncClientIdentity,
    private val backend: SyncBackendSupabaseProvider,
    private val auth: dagger.Lazy<AuthManager>,
    private val profiles: dagger.Lazy<ProfileManager>,
) {
    private val storage = ProtectedProfilePreferences(context,"device_sources","com.tuvora.device.sources.v1")
    private val json = Json { ignoreUnknownKeys = true }
    private var key: String? = null
    private var controller: DeviceSourceEngine? = null
    init { instance = this }
    @Synchronized fun engine(): DeviceSourceEngine {
        val manager = auth.get()
        val account = manager.currentUserId
        val next = "${backend.selectedBackend.normalizedSupabaseUrl}:${account ?: "guest"}"
        if (next != key || controller == null) {
            val client = if (account == null) null else backend.client
            controller = DeviceSourceEngine(object : DevicePreferenceStorage {
                override fun read(): String? = storage.read(1)?.let { json.decodeFromString<Map<String,String>>(it)[next] }
                override fun write(value: String) {
                    val all = storage.read(1)?.let { json.decodeFromString<Map<String,String>>(it) }.orEmpty()
                    storage.write(1,json.encodeToString(all + (next to value)))
                }
            }, rpc = { name,args -> checkNotNull(client).postgrest.rpc(name,args).decodeAs<JsonElement>() },
                uuid = { UUID.randomUUID().toString() }, credential = {
                    ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
                }, now = { System.currentTimeMillis() }, platform = "tv", localOnly = account == null,
                automaticSetupName = com.nuvio.tv.core.auth.currentDeviceClientMetadata(context).deviceName.take(120),
                existingInstallation = identity.sourceSetupUpgradeEligible,
                onRefused = ::logRefusal)
            key = next
        }
        return controller!!
    }
    fun profile() = profiles.get().activeProfileId.value
    suspend fun refresh() = engine().refresh(profile())
    companion object {
        val changes = kotlinx.coroutines.flow.MutableStateFlow(0L)
        val showSettings = kotlinx.coroutines.flow.MutableStateFlow(false)
        fun changed() { changes.value += 1 }
        val epoch: Long get() = instance?.engine()?.state?.value?.availabilityEpoch ?: 0
        @Volatile private var instance: DeviceSources? = null
        fun allows(kind: String, ref: String): Boolean = instance?.let { it.auth.get().authState.value != com.nuvio.tv.domain.model.AuthState.Loading && it.engine().allows(it.profile(),kind,ref) } ?: true
        fun addonAllowed(url: String): Boolean = instance?.let { it.auth.get().authState.value != com.nuvio.tv.domain.model.AuthState.Loading && it.engine().allowsAddonRequest(it.profile(),url) } ?: true
        /** Cache provenance is an opaque UUID; configured addon URLs never enter the link cache. */
        fun sourceId(kind: String?, ref: String?): String? {
            val owner = instance ?: return null
            if (kind == null || ref == null) return null
            return owner.engine().state.value.disk.sources[owner.profile()].orEmpty().filter {
                it.kind == kind && (it.sourceRef == ref || (kind == "addon" &&
                    it.sourceRef.substringBeforeLast("/manifest.json",it.sourceRef).trimEnd('/') == ref.trimEnd('/')))
            }.singleOrNull()?.sourceId
        }
        fun allowsSourceId(id: String): Boolean {
            val owner = instance ?: return false
            val engine = owner.engine()
            val view = engine.state.value
            return view.ready && view.disk.sources[owner.profile()].orEmpty().any {
                it.sourceId == id && it.accountEnabled && engine.choice(owner.profile(),id)
            }
        }
        /** One line per refusal: source kind and engine mode only, never a host, id or URL. */
        internal fun logRefusal(kind: String, mode: String) { android.util.Log.i("DeviceSources", "source refused on this device: kind=$kind mode=$mode") }
        private fun refuse(kind: String): Nothing {
            val mode = instance?.engine()?.state?.value?.mode ?: "unknown"
            logRefusal(kind, mode)
            throw SourceDisabledOnDeviceException(kind, mode)
        }
        private suspend fun currentAccess() = currentCoroutineContext()[DeviceSourceAccessElement]?.access ?: DeviceSourceAccess.DISCOVERY
        fun guard(kind: String, ref: String) { if (!allows(kind,ref)) refuse(kind) }
        /** [guard] for suspend callers: honours [verifying] / [inspecting] for the current request. */
        suspend fun checkAccess(kind: String, ref: String) {
            val owner = instance ?: return
            when (val access = currentAccess()) {
                DeviceSourceAccess.INSPECT -> return
                DeviceSourceAccess.DISCOVERY -> guard(kind, ref)
                else -> if (!owner.engine().permits(access, owner.profile(), kind, ref)) refuse(kind)
            }
        }
        suspend fun <T> guarded(kind: String, ref: String, block: suspend () -> T): T {
            val owner = instance ?: return block()
            return owner.engine().guarded(owner.profile(),kind,ref,currentAccess(),block)
        }
        /** The add/edit form's live check of a playlist: allowed before the device has a choice for it. */
        suspend fun <T> verifying(block: suspend () -> T): T = withContext(DeviceSourceAccessElement(DeviceSourceAccess.VERIFY)) { block() }
        /** A read-only account lookup the user opened (expiry, connections): allowed for a source turned off here. */
        suspend fun <T> inspecting(block: suspend () -> T): T = withContext(DeviceSourceAccessElement(DeviceSourceAccess.INSPECT)) { block() }
        fun guardAddon(url: String) { if (!addonAllowed(url)) refuse("addon") }
    }
}
