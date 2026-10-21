package com.nuvio.tv.core.sources

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

interface DevicePreferenceStorage {
    fun read(): String?
    /** Must atomically replace the value, or throw. Never acknowledge a failed write. */
    fun write(value: String)
}

@Serializable
data class SavedDeviceBinding(
    val id: String, val label: String, val secret: String, val generation: Long = 1,
    val registration: String? = null, val copyFrom: String? = null,
) { override fun toString() = "SavedDeviceBinding(redacted)" }

@Serializable
data class PendingDeviceChoice(
    val mutation: String, val profile: Int, val source: String, val desired: Boolean?,
    val requestValue: Boolean? = desired, val expectedRevision: Long = 0,
    val sentAt: Long? = null, val conflict: Boolean = false,
)

@Serializable
data class DevicePreferenceDisk(
    val binding: SavedDeviceBinding? = null,
    val cursor: Long? = null,
    val values: List<DevicePreferenceValue> = emptyList(),
    val pending: List<PendingDeviceChoice> = emptyList(),
    val sources: Map<Int, List<DeviceSourceDescriptor>> = emptyMap(),
    // Guest identities outlive transient empty repository emissions during startup. Encrypted at rest.
    val localRegistry: List<DeviceSourceDescriptor> = emptyList(),
)

data class DeviceSourceView(
    val disk: DevicePreferenceDisk = DevicePreferenceDisk(),
    val setups: List<DeviceSetupSummary> = emptyList(),
    val mode: String = "loading", // loading, select, ready, local, unavailable, replaced
    val message: String? = null,
    val busy: Boolean = false,
    val revision: Long = 0,
    val availabilityEpoch: Long = 0,
) {
    val ready get() = mode == "ready" || mode == "local"
}

/** One controller per backend/account. All durable state (including the outbox) is one atomic blob. */
class DeviceSourceEngine(
    private val store: DevicePreferenceStorage,
    private val rpc: suspend (String, JsonObject) -> JsonElement,
    private val uuid: () -> String,
    private val credential: () -> String,
    private val now: () -> Long,
    private val platform: String,
    private val localOnly: Boolean = false,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val network = Mutex()
    private val loaded = runCatching { store.read()?.let { json.decodeFromString<DevicePreferenceDisk>(it) } ?: DevicePreferenceDisk() }
    private val restored = loaded.getOrDefault(DevicePreferenceDisk())
    private val mutable = MutableStateFlow(DeviceSourceView(restored, mode = when {
        loaded.isFailure -> "storage_error"
        localOnly -> "local"
        restored.binding != null && restored.cursor != null -> "ready"
        else -> "loading"
    }, message = if (loaded.isFailure) "Could not read saved choices. Restore a setup before using these sources." else null))
    val state = mutable.asStateFlow()

    private fun policy(v: DeviceSourceView): List<String> {
        val keys = (v.disk.values.map { it.profileId to it.sourceId } + v.disk.pending.map { it.profile to it.source }).distinct()
        return listOf(if (v.ready) "ready" else v.mode) + keys.map { (profile,source) ->
            val pending = v.disk.pending.firstOrNull { it.profile == profile && it.source == source }
            val effective = DeviceSourcePolicy.effectiveChoice(DeviceSourceInputs(true,true,true,true,
                acknowledgedChoice = v.disk.values.firstOrNull { it.profileId == profile && it.sourceId == source }?.enabled,
                pendingChoice = pending?.desired, hasPendingChoice = pending != null, pendingConflict = pending?.conflict == true))
            "$profile:$source:$effective"
        }.sorted() + v.disk.sources.flatMap { (p,s) -> s.map { "$p:${it.sourceId}:${it.kind}:${it.sourceRef}:${it.accountEnabled}" } }.sorted()
    }
    private fun update(next: DeviceSourceView) {
        val before = mutable.value
        mutable.value = next.copy(revision = before.revision + 1,
            availabilityEpoch = before.availabilityEpoch + if (policy(before) == policy(next)) 0 else 1)
    }
    private fun publish(transform: (DeviceSourceView) -> DeviceSourceView) = synchronized(lock) {
        update(transform(mutable.value))
    }
    private fun commit(onlyIfChanged: Boolean = false, transform: (DevicePreferenceDisk) -> DevicePreferenceDisk) = synchronized(lock) {
        val disk = transform(mutable.value.disk)
        if (onlyIfChanged && disk == mutable.value.disk) return@synchronized
        store.write(json.encodeToString(disk))
        update(mutable.value.copy(disk = disk))
    }

    fun choice(profile: Int, source: String): Boolean {
        val d = state.value.disk
        val pending = d.pending.firstOrNull { it.profile == profile && it.source == source }
        return DeviceSourcePolicy.effectiveChoice(DeviceSourceInputs(true,true,true,true,
            acknowledgedChoice = d.values.firstOrNull { it.profileId == profile && it.sourceId == source }?.enabled,
            pendingChoice = pending?.desired, hasPendingChoice = pending != null, pendingConflict = pending?.conflict == true))
    }

    private fun knownSources(profile: Int): List<DeviceSourceDescriptor> {
        val d = state.value.disk
        val current = d.sources[profile].orEmpty()
        if (!localOnly) return current
        val currentKeys = current.map { it.kind to it.sourceRef }.toSet()
        val history = (d.localRegistry + d.sources.values.flatten()).distinctBy { it.kind to it.sourceRef }
        // A loading list must not temporarily permit a source whose saved guest choice is off.
        return current + history.filter { (it.kind to it.sourceRef) !in currentKeys }
            .map { it.copy(accountEnabled = true, storageProfile = profile) }
    }

    fun allows(profile: Int, kind: String, ref: String): Boolean {
        val view = state.value
        if (view.mode == "unavailable") return true // Old/self-hosted backend: existing behavior, no false controls.
        if (!view.ready || (!localOnly && profile !in view.disk.sources)) return false
        val sources = knownSources(profile).filter { it.kind == kind && it.sourceRef == ref }
        return sources.all { it.accountEnabled && choice(profile,it.sourceId) }
    }

    /** Cancel discovery for a disabled source, including requests already in flight. */
    suspend fun <T> guarded(profile: Int, kind: String, ref: String, block: suspend () -> T): T = kotlinx.coroutines.coroutineScope {
        if (!allows(profile,kind,ref)) throw CancellationException("Source disabled on this device")
        val task = async { block() }
        val watcher = launch {
            state.collect {
                if (!allows(profile,kind,ref)) task.cancel(CancellationException("Source disabled on this device"))
            }
        }
        try { task.await().also { if (!allows(profile,kind,ref)) throw CancellationException("Source disabled on this device") } }
        finally { watcher.cancel() }
    }

    fun allowsAddonRequest(profile: Int, url: String): Boolean {
        if (state.value.mode == "unavailable") return true
        if (!state.value.ready || (!localOnly && profile !in state.value.disk.sources)) return false
        val rawRequest = url.trim().substringBefore('#')
        val requested = DeviceSourcePolicy.addonReference(rawRequest)
        val matches = knownSources(profile).filter {
            if (it.kind != "addon") false else {
                val base = DeviceSourcePolicy.addonReference(it.sourceRef)
                val query = base.substringAfter('?', "")
                requested == base || (rawRequest.substringBefore('?').startsWith(base.substringBefore('?') + "/") &&
                    ('?' !in base || ('?' in rawRequest && rawRequest.substringAfter('?', "") == query)))
            }
        }
        // Configured addons on one host are independent. Prefer an exact source, then the
        // longest configured path and its matching query, rather than a broader host/root addon.
        fun specificity(source: DeviceSourceDescriptor): Int {
            val base = DeviceSourcePolicy.addonReference(source.sourceRef)
            return base.substringBefore('?').length * 2 + if ('?' in base) 1 else 0
        }
        val exact = matches.filter { DeviceSourcePolicy.addonReference(it.sourceRef) == requested }
        val longest = matches.maxOfOrNull(::specificity)
        val selected = if (exact.isNotEmpty()) exact else matches.filter { specificity(it) == longest }
        return selected.all { it.accountEnabled && choice(profile,it.sourceId) }
    }

    fun setChoice(profile: Int, source: String, enabled: Boolean?) {
        check(state.value.ready) { "Choose a device setup first" }
        check(state.value.disk.sources[profile].orEmpty().any { it.sourceId == source }) { "Source unavailable" }
        commit { d ->
            if (localOnly) d.copy(values = d.values.filterNot { it.profileId == profile && it.sourceId == source } +
                DevicePreferenceValue(profile,source,enabled,0))
            else {
                val old = d.pending.firstOrNull { it.profile == profile && it.source == source }
                val pending = if (old?.sentAt != null) old.copy(desired = enabled)
                else PendingDeviceChoice(uuid(),profile,source,enabled,
                    expectedRevision = d.values.firstOrNull { it.profileId == profile && it.sourceId == source }?.revision ?: 0)
                d.copy(pending = d.pending.filterNot { it.profile == profile && it.source == source } + pending)
            }
        }
    }

    fun resolve(profile: Int, source: String, applyMine: Boolean) {
        commit { d ->
            val old = d.pending.firstOrNull { it.profile == profile && it.source == source } ?: return@commit d
            val pending = d.pending.filterNot { it.profile == profile && it.source == source }
            d.copy(pending = if (!applyMine) pending else pending + PendingDeviceChoice(uuid(),profile,source,old.desired,
                expectedRevision = d.values.firstOrNull { it.profileId == profile && it.sourceId == source }?.revision ?: 0))
        }
    }

    fun localSources(profile: Int, sources: List<DeviceSourceDescriptor>) {
        if (!localOnly) return
        commit(onlyIfChanged = true) { d ->
            val registry = (d.localRegistry + d.sources.values.flatten()).distinctBy { it.kind to it.sourceRef }
            val stable = sources.map { incoming ->
                val saved = registry.firstOrNull { it.kind == incoming.kind && it.sourceRef == incoming.sourceRef }
                incoming.copy(sourceId = saved?.sourceId ?: incoming.sourceId)
            }
            d.copy(sources = d.sources + (profile to stable),
                localRegistry = (registry + stable).distinctBy { it.kind to it.sourceRef })
        }
    }
    fun startLocal() {
        check(localOnly)
        commit { DevicePreferenceDisk() }
        publish { it.copy(mode = "local",message = null) }
    }

    suspend fun select(label: String, existing: DeviceSetupSummary? = null, copy: Boolean = false, profile: Int) {
        require(label.trim().isNotEmpty())
        val binding = SavedDeviceBinding(if (existing != null && !copy) existing.id else uuid(), label.trim(), credential(),
            generation = existing?.takeUnless { copy }?.generation ?: 1,
            registration = if (existing != null && !copy) "restore" else "create", copyFrom = existing?.takeIf { copy }?.id)
        commit { DevicePreferenceDisk(binding = binding, sources = it.sources) }
        publish { it.copy(mode = "loading") }
        refresh(profile)
    }

    suspend fun refresh(profile: Int, refreshSources: Boolean = true, forceDelta: Boolean = true) = network.withLock {
        if (localOnly) return@withLock
        val disk = state.value.disk
        if (!refreshSources && !forceDelta && !DevicePreferenceRefreshPolicy.hasLocalWork(
                disk.pending.any { !it.conflict },disk.binding?.registration != null,disk.binding != null && disk.cursor == null)) return@withLock
        publish { it.copy(busy = true, message = null) }
        try {
            withTimeout(12000) {
                var b = state.value.disk.binding
                val draft = b
                if (draft?.registration != null) {
                    val result = if (draft.registration == "restore") call<DeviceSetupResult>("device_setup_restore", buildJsonObject {
                        put("p_setup",draft.id); put("p_expected_generation",draft.generation); put("p_binding",draft.secret)
                    }) else call<DeviceSetupResult>("device_setup_create", buildJsonObject {
                        put("p_setup",draft.id); put("p_label",draft.label); put("p_platform",platform); put("p_binding",draft.secret)
                        put("p_copy_from",draft.copyFrom?.let(::JsonPrimitive) ?: JsonNull)
                    })
                    b = draft.copy(generation = result.generation, registration = null)
                    val registered = b
                    commit { it.copy(binding = registered) }
                }
                var sourcesRead = false
                if (refreshSources || profile !in state.value.disk.sources) {
                    val sources = call<List<DeviceSourceDescriptor>>("device_sources_list", buildJsonObject { put("p_profile",profile) })
                    commit { it.copy(sources = it.sources + (profile to sources)) }
                    sourcesRead = true
                    val setups = call<List<DeviceSetupSummary>>("device_setups_list", buildJsonObject {})
                    publish { it.copy(setups = setups) }
                }
                if (b == null) { publish { it.copy(mode = "select") }; return@withTimeout }
                val active = checkNotNull(b)
                var until: Long? = null
                var more: Boolean
                do {
                    val page = call<DevicePreferencePage>("device_preferences_read", buildJsonObject {
                        put("p_setup",active.id); put("p_cursor",state.value.disk.cursor?.let(::JsonPrimitive) ?: JsonNull)
                        put("p_limit",200); until?.let { put("p_until",it) }
                    })
                    if (page.generation != active.generation) throw IllegalStateException("binding_replaced")
                    // A profile swap/delete resets the cursor; refresh its source-to-UUID mapping too.
                    val resetSources = if (page.snapshot && !sourcesRead)
                        call<List<DeviceSourceDescriptor>>("device_sources_list",buildJsonObject { put("p_profile",profile) }) else null
                    if (resetSources != null) sourcesRead = true
                    commit { d ->
                        val base = if (page.snapshot) emptyList() else d.values
                        d.copy(cursor = page.cursor, sources = if (resetSources == null) d.sources else d.sources + (profile to resetSources), values = base.filterNot { old -> page.values.any {
                            it.profileId == old.profileId && it.sourceId == old.sourceId
                        } } + page.values)
                    }
                    until = page.revision; more = page.hasMore
                } while (more)
                publish { it.copy(mode = "ready") }
                flush(active)
            }
        } catch (e: TimeoutCancellationException) { failure(e) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure(e) }
        finally { publish { it.copy(busy = false) } }
    }

    private suspend fun flush(b: SavedDeviceBinding) {
        repeat(50) {
            if (state.value.disk.pending.none { !it.conflict }) return
            var request: PendingDeviceChoice? = null
            commit { d ->
                val p = d.pending.firstOrNull { !it.conflict } ?: return@commit d
                val expired = p.sentAt != null && now() - p.sentAt > 30L*24*60*60*1000
                val marked = if (expired) p.copy(conflict = true) else
                    if (p.sentAt == null) p.copy(sentAt = now(),requestValue = p.desired) else p
                if (!expired) request = marked
                d.copy(pending = d.pending.map { if (it.mutation == p.mutation) marked else it })
            }
            val sent = request ?: return@repeat
            val result = try { call<DevicePreferenceWriteResult>("device_preference_write", buildJsonObject {
                put("p_setup",b.id); put("p_generation",b.generation); put("p_binding",b.secret)
                put("p_mutation",sent.mutation); put("p_profile",sent.profile); put("p_source",sent.source)
                put("p_enabled",sent.requestValue?.let(::JsonPrimitive) ?: JsonNull); put("p_expected_revision",sent.expectedRevision)
            }) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if ("source_not_visible" !in e.message.orEmpty()) throw e
                // A removed source's intent cannot be replayed against its replacement UUID.
                commit { d -> d.copy(pending = d.pending.filterNot { it.mutation == sent.mutation }) }
                return@repeat
            }
            commit { d ->
                val latest = d.pending.firstOrNull { it.mutation == sent.mutation } ?: return@commit d
                val rest = d.pending.filterNot { it.mutation == sent.mutation }
                val next = if (!result.ok) latest.copy(conflict = true)
                    else if (latest.desired != sent.requestValue) PendingDeviceChoice(uuid(),sent.profile,sent.source,latest.desired,expectedRevision = result.revision)
                    else null
                d.copy(values = d.values.filterNot { it.profileId == sent.profile && it.sourceId == sent.source } +
                    DevicePreferenceValue(sent.profile,sent.source,result.enabled,result.revision), pending = rest + listOfNotNull(next))
            }
        }
    }

    private fun failure(e: Exception) {
        val text = e.message.orEmpty()
        publish { view -> when {
            "binding_replaced" in text || "setup_not_found" in text -> view.copy(mode = "replaced", message = "This setup was restored or removed elsewhere. Choose a setup again.")
            view.disk.binding == null && ("PGRST202" in text || "Could not find the function" in text) ->
                view.copy(mode = "unavailable", message = "Device controls need a server update.")
            else -> view.copy(message = if (view.disk.pending.isNotEmpty()) "Saved on this device · waiting to sync" else "Could not reach your account. Retry.")
        } }
    }

    private suspend inline fun <reified T> call(name: String, args: JsonObject): T = json.decodeFromJsonElement(rpc(name,args))
}
