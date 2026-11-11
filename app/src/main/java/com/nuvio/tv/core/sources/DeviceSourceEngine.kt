package com.nuvio.tv.core.sources

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * The device-source guard refused a request: the source is turned off on this device, or the device's
 * choices are not known yet. A [CancellationException] so every existing "the work was cancelled" path
 * keeps its meaning, but a type of its own so callers can tell it from a real cancellation and from a
 * network failure (B224: it used to read as "Couldn't reach the server"). Carries only the source [kind]
 * and the engine [mode] - never a host, id or URL.
 */
class SourceDisabledOnDeviceException(
    val kind: String, val mode: String, val scope: RefusalScope = RefusalScope.DEVICE,
) : CancellationException("Source disabled on this device")

/** Who turned the source off: this device's own choice, or the account (the playlist is off for every device). */
enum class RefusalScope { DEVICE, ACCOUNT, SIGNING_IN }

/**
 * Why a request reaches a source. [DISCOVERY] (the default) is playback and catalog use: it needs the
 * device's explicit permission. [VERIFY] is the add/edit form's live check of a playlist the user is typing in
 * and [INSPECT] is a read-only account lookup the user opened (expiry, connections). Neither can ENABLE
 * anything - they only decide whether one request may be made - and VERIFY still refuses a source this
 * device knows to be off.
 */
enum class DeviceSourceAccess { DISCOVERY, VERIFY, INSPECT }

/** Carries the [DeviceSourceAccess] of the current request through the call stack. */
class DeviceSourceAccessElement(val access: DeviceSourceAccess) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DeviceSourceAccessElement>
}

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

/** Why the last refresh failed, so the screen can say something true (B188). Never carries a host or message. */
enum class DeviceSourceFailure { UNREACHABLE, SIGNED_OUT, TIMED_OUT }

data class DeviceSourceView(
    val disk: DevicePreferenceDisk = DevicePreferenceDisk(),
    val setups: List<DeviceSetupSummary> = emptyList(),
    val mode: String = "loading", // loading, select, ready, local, unavailable, replaced
    val message: String? = null,
    val failure: DeviceSourceFailure? = null,
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
    private val automaticSetupName: String? = null,
    // No longer changes the outcome (every device sets itself up); kept so callers and old tests compile.
    @Suppress("unused") private val existingInstallation: Boolean = false,
    // One line per refusal (source kind + engine mode; no host). Injected so the engine stays pure.
    private val onRefused: (kind: String, mode: String, scope: RefusalScope) -> Unit = { _, _, _ -> },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val network = Mutex()
    private fun readDisk() = runCatching { store.read()?.let { json.decodeFromString<DevicePreferenceDisk>(it) } ?: DevicePreferenceDisk() }
    private val loaded = runCatching { store.read()?.let { json.decodeFromString<DevicePreferenceDisk>(it) } ?: DevicePreferenceDisk() }
    private var storageReadable = loaded.isSuccess
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

    /** Whether a request of [access] may reach this source now. Pure: it never changes a choice. */
    fun permits(access: DeviceSourceAccess, profile: Int, kind: String, ref: String): Boolean = when (access) {
        DeviceSourceAccess.DISCOVERY -> allows(profile, kind, ref)
        DeviceSourceAccess.INSPECT -> true
        // An unsaved playlist has no device choice, and a not-ready engine cannot say; only a source this
        // device positively knows to be off is refused.
        DeviceSourceAccess.VERIFY -> state.value.mode == "unavailable" ||
            knownSources(profile).filter { it.kind == kind && it.sourceRef == ref }.all { it.accountEnabled && choice(profile, it.sourceId) }
    }

    /** Whether the account itself (not this device) has [ref] off: any matching source with accountEnabled == false. */
    fun refusalScope(profile: Int, kind: String, ref: String): RefusalScope =
        if (knownSources(profile).any { it.kind == kind && it.sourceRef == ref && !it.accountEnabled }) RefusalScope.ACCOUNT else RefusalScope.DEVICE

    /** Throws the typed refusal (and reports it). */
    fun refuse(kind: String, scope: RefusalScope = RefusalScope.DEVICE): Nothing {
        val mode = state.value.mode
        onRefused(kind, mode, scope)
        throw SourceDisabledOnDeviceException(kind, mode, scope)
    }

    fun allows(profile: Int, kind: String, ref: String): Boolean {
        val view = state.value
        if (view.mode == "unavailable") return true // Old/self-hosted backend: existing behavior, no false controls.
        if (!view.ready || (!localOnly && profile !in view.disk.sources)) return false
        val sources = knownSources(profile).filter { it.kind == kind && it.sourceRef == ref }
        return sources.all { it.accountEnabled && choice(profile,it.sourceId) }
    }

    /** Cancel discovery for a disabled source, including requests already in flight. */
    suspend fun <T> guarded(
        profile: Int, kind: String, ref: String,
        access: DeviceSourceAccess = DeviceSourceAccess.DISCOVERY, block: suspend () -> T,
    ): T = kotlinx.coroutines.coroutineScope {
        if (!permits(access,profile,kind,ref)) refuse(kind, refusalScope(profile,kind,ref))
        if (access == DeviceSourceAccess.INSPECT) return@coroutineScope block()
        val task = async { block() }
        val watcher = launch {
            state.collect {
                if (!permits(access,profile,kind,ref)) task.cancel(SourceDisabledOnDeviceException(kind, state.value.mode, refusalScope(profile,kind,ref)))
            }
        }
        try { task.await().also { if (!permits(access,profile,kind,ref)) refuse(kind, refusalScope(profile,kind,ref)) } }
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

    /**
     * A storage read that failed at start-up (keychain not yet readable before first unlock, a transient
     * I/O error) is retried on the next refresh instead of sticking until relaunch (B224).
     */
    private fun rereadStorageIfFailed() = synchronized(lock) {
        if (mutable.value.mode != "storage_error") return@synchronized
        val disk = readDisk().getOrNull() ?: return@synchronized
        storageReadable = true
        update(mutable.value.copy(disk = disk, message = null, mode = when {
            localOnly -> "local"
            disk.binding != null && disk.cursor != null -> "ready"
            else -> "loading"
        }))
    }

    suspend fun refresh(profile: Int, refreshSources: Boolean = true, forceDelta: Boolean = true) = network.withLock {
        rereadStorageIfFailed()
        if (localOnly) return@withLock
        val disk = state.value.disk
        if (!refreshSources && !forceDelta && !DevicePreferenceRefreshPolicy.hasLocalWork(
                disk.pending.any { !it.conflict },disk.binding?.registration != null,disk.binding != null && disk.cursor == null)) return@withLock
        publish { it.copy(busy = true, message = null, failure = null) }
        try {
            run {
                var b = state.value.disk.binding
                val draft = b
                if (draft?.registration != null) {
                    b = registerBinding(draft)
                }
                var sourcesRead = false
                if (refreshSources || profile !in state.value.disk.sources) {
                    val sources = call<List<DeviceSourceDescriptor>>("device_sources_list", buildJsonObject { put("p_profile",profile) })
                    commit { it.copy(sources = it.sources + (profile to sources)) }
                    sourcesRead = true
                    val setups = call<List<DeviceSetupSummary>>("device_setups_list", buildJsonObject {})
                    publish { it.copy(setups = setups) }
                }
                // A device with no setup of its own uses the account's sources without asking (owner
                // decision 2026-11-02), even when other devices saved setups: restoring one stays
                // available under "Sources on this device". Never guess by device name.
                if (b == null && storageReadable && !automaticSetupName.isNullOrBlank()) {
                    val automatic = SavedDeviceBinding(uuid(), automaticSetupName.trim(), credential(), registration = "create")
                    commit { it.copy(binding = automatic) } // Persist identity before the network for safe retries.
                    b = registerBinding(automatic)
                }
                if (b == null) { publish { it.copy(mode = "select") }; return@run }
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

    private suspend fun registerBinding(draft: SavedDeviceBinding): SavedDeviceBinding {
        val result = if (draft.registration == "restore") call<DeviceSetupResult>("device_setup_restore", buildJsonObject {
            put("p_setup",draft.id); put("p_expected_generation",draft.generation); put("p_binding",draft.secret)
        }) else call<DeviceSetupResult>("device_setup_create", buildJsonObject {
            put("p_setup",draft.id); put("p_label",draft.label); put("p_platform",platform); put("p_binding",draft.secret)
            put("p_copy_from",draft.copyFrom?.let(::JsonPrimitive) ?: JsonNull)
        })
        val registered = draft.copy(generation = result.generation, registration = null)
        commit { it.copy(binding = registered) }
        return registered
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

    private fun failureKind(e: Exception): DeviceSourceFailure {
        if (e is TimeoutCancellationException) return DeviceSourceFailure.TIMED_OUT
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth++ < 8) {
            val code = when (cause) {
                is io.github.jan.supabase.exceptions.RestException -> cause.statusCode
                is io.ktor.client.plugins.ResponseException -> cause.response.status.value
                else -> null
            }
            if (code == 401 || code == 403) return DeviceSourceFailure.SIGNED_OUT
            if (cause is java.net.SocketTimeoutException || cause is io.ktor.client.plugins.HttpRequestTimeoutException ||
                cause is io.ktor.client.network.sockets.ConnectTimeoutException ||
                cause is io.ktor.client.network.sockets.SocketTimeoutException) return DeviceSourceFailure.TIMED_OUT
            cause = cause.cause?.takeIf { it !== cause }
        }
        return DeviceSourceFailure.UNREACHABLE
    }

    private fun failure(e: Exception) {
        val text = e.message.orEmpty()
        val kind = failureKind(e)
        publish { view -> when {
            "binding_replaced" in text || "setup_not_found" in text -> view.copy(mode = "replaced", message = "This setup was restored or removed elsewhere. Choose a setup again.")
            view.disk.binding == null && ("PGRST202" in text || "Could not find the function" in text) ->
                view.copy(mode = "unavailable", message = "Device controls need a server update.")
            // The screen words the failure from [failure] (strings.xml); a saved-but-unsynced choice keeps its own line.
            else -> view.copy(mode = if (view.mode == "loading") "select" else view.mode, failure = kind,
                message = if (view.disk.pending.isNotEmpty()) "Saved on this device · waiting to sync" else null)
        } }
    }

    // One deadline per call, not one around all of them: slow-but-working calls must not add up to a timeout.
    private suspend inline fun <reified T> call(name: String, args: JsonObject): T =
        json.decodeFromJsonElement(withTimeout(CALL_TIMEOUT_MS) { rpc(name,args) })

    private companion object { const val CALL_TIMEOUT_MS = 12_000L }
}
