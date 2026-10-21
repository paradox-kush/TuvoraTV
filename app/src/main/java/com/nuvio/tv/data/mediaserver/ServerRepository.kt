package com.nuvio.tv.data.mediaserver

import android.util.Log
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.random.Random

data class ServersUiState(
    val connections: List<ServerConnection> = emptyList(),
    val failures: Map<String, ServerFailure> = emptyMap(),
    val revision: Int = 0,
    val loadingLibraries: Set<String> = emptySet()
) {
    val enabledConnections: List<ServerConnection>
        get() = connections.filter { it.availableOnDevice }
}

class ServerRepository(
    private val persistence: ServerPersistence,
    val providers: List<ServerProvider>,
    private val scope: CoroutineScope,
    initialProfileId: Int = 1,
    private val trust: ServerTrust? = null
) : ProfileScopedCredentialStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val mutableState = MutableStateFlow(ServersUiState())
    val uiState: StateFlow<ServersUiState> = mutableState.asStateFlow()

    @Volatile
    private var activeProfileId = initialProfileId

    @Volatile
    private var loadedProfileId: Int? = null

    @Volatile
    private var generation = 0L
    private var tokens = emptyMap<String, String>()
    private var pendingPush = false
    private var syncedKeys: Set<String>? = null
    private var libraryRefreshPending = false
    private var localVersion = 0L
    private val mutableLocalChanges = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val localChanges: SharedFlow<Int> = mutableLocalChanges.asSharedFlow()

    fun onDeviceSourcesChanged() {
        synchronized(lock) {
            generation += 1
            libraryLoadEpoch += 1
            mutableState.update { it.copy(revision = it.revision + 1) }
        }
    }
    fun ensureLoaded() {
        synchronized(lock) {
            val profileId = activeProfileId
            if (loadedProfileId != profileId) load(profileId)
        }
    }

    fun selectProfile(profileId: Int) {
        activeProfileId = profileId
        ensureLoaded()
    }

    fun connection(connectionId: String): ServerConnection? {
        ensureLoaded()
        return mutableState.value.connections.firstOrNull { it.id == connectionId }
    }

    fun enabledConnections(): List<ServerConnection> {
        ensureLoaded()
        return mutableState.value.enabledConnections
    }

    fun provider(connection: ServerConnection): ServerProvider? = providers.firstOrNull { it.id == connection.providerId }

    fun sourceLabel(connection: ServerConnection): String =
        "${provider(connection)?.displayName ?: connection.providerId} · ${connection.name}"

    fun session(connectionId: String): ServerSession? {
        val connection = connection(connectionId)?.takeIf { it.availableOnDevice } ?: return null
        val token = synchronized(lock) { tokens[connection.credentialRef] } ?: return null
        return ServerSession(connection, token)
    }

    suspend fun <T> call(
        connectionId: String,
        block: suspend (ServerProvider, ServerSession) -> T
    ): T {
        val connection = connection(connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        if (!connection.availableOnDevice) throw ServerException(ServerFailure.UNREACHABLE)
        val session = session(connectionId) ?: throw ServerException(ServerFailure.AUTH_REQUIRED)
        val provider = provider(session.connection) ?: throw ServerException(ServerFailure.UNSUPPORTED)
        val startedScope = libraryLoadEpoch
        fun isCurrent() = startedScope == libraryLoadEpoch &&
            mutableState.value.connections.firstOrNull { it.id == connectionId } == connection
        return try {
            com.nuvio.tv.core.sources.DeviceSources.guarded("media_server","${connection.providerId}|${connection.remoteServerId}|${connection.remoteUserId}") { block(provider, session) }.also {
                if (!isCurrent()) throw CancellationException("Server scope changed")
                setFailure(connectionId, null, startedScope, connection)
            }
        } catch (error: ServerException) {
            if (isCurrent() &&
                (error.failure == ServerFailure.AUTH_REQUIRED || error.failure == ServerFailure.UNREACHABLE ||
                    error.failure == ServerFailure.CERTIFICATE_UNTRUSTED || error.failure == ServerFailure.CERTIFICATE_CHANGED)
            ) {
                setFailure(connectionId, error.failure, startedScope, connection)
            }
            throw error
        }
    }

    suspend fun connect(provider: ServerProvider, address: String, username: String, password: String): ServerConnection {
        ensureLoaded()
        val startedGeneration = generation
        val profileId = loadedProfileId ?: activeProfileId
        return storeSignIn(provider, provider.signIn(address, username, password), startedGeneration, profileId)
    }

    /** Stores a sign-in made elsewhere (Quick Connect): the token it carries is this device's own. */
    suspend fun connect(provider: ServerProvider, signIn: ServerSignIn): ServerConnection {
        ensureLoaded()
        return storeSignIn(provider, signIn, generation, loadedProfileId ?: activeProfileId)
    }

    private suspend fun storeSignIn(
        provider: ServerProvider,
        signIn: ServerSignIn,
        startedGeneration: Long,
        profileId: Int
    ): ServerConnection {
        val existing = mutableState.value.connections.firstOrNull {
            it.providerId == provider.id &&
                it.remoteServerId == signIn.serverId &&
                it.remoteUserId == signIn.userId
        }
        val draft = ServerConnection(
            id = existing?.id ?: newId("c"),
            providerId = provider.id,
            name = signIn.serverName,
            address = signIn.address,
            remoteServerId = signIn.serverId,
            remoteUserId = signIn.userId,
            userName = signIn.userName,
            credentialRef = newId("k"),
            libraries = existing?.libraries.orEmpty(),
            enabled = true,
            useCatalogMetadata = existing?.useCatalogMetadata ?: false,
            importWatchState = existing?.importWatchState ?: false
        )
        val libraries = provider.libraries(ServerSession(draft, signIn.token))
        if (startedGeneration != generation || profileId != loadedProfileId) {
            throw CancellationException("Server scope changed")
        }
        val connection = draft.copy(libraries = mergeLibraries(existing?.libraries.orEmpty(), libraries))
        store(connection, signIn.token, replacedCredential = existing?.credentialRef)
        return connection
    }

    internal fun store(connection: ServerConnection, token: String, replacedCredential: String? = null) {
        ensureLoaded()
        val updatedTokens = synchronized(lock) {
            (tokens - listOfNotNull(replacedCredential)) + (connection.credentialRef to token)
        }
        save(
            mutableState.value.connections.filterNot { it.id == connection.id } + connection,
            updatedTokens
        )
        setFailure(connection.id, null)
    }

    suspend fun refreshLibraries(connectionId: String) {
        ensureLoaded()
        val epoch = libraryLoadEpoch
        val libraries = call(connectionId) { provider, session -> provider.libraries(session) }
        updateConnection(connectionId, expectedEpoch = epoch) { it.copy(libraries = mergeLibraries(it.libraries, libraries)) }
    }

    fun setLibrarySelected(connectionId: String, libraryId: String, selected: Boolean) {
        updateConnection(connectionId) { connection ->
            connection.copy(
                libraries = connection.libraries.map {
                    if (it.id == libraryId) it.copy(selected = selected) else it
                }
            )
        }
    }

    fun setCatalogMetadata(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(useCatalogMetadata = enabled) }
    }

    fun setImportWatchState(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(importWatchState = enabled) }
    }

    fun setEnabled(connectionId: String, enabled: Boolean) {
        updateConnection(connectionId) { it.copy(enabled = enabled) }
    }

    fun remove(connectionId: String) {
        val connection = connection(connectionId) ?: return
        val session = session(connectionId)
        save(
            mutableState.value.connections.filterNot { it.id == connectionId },
            synchronized(lock) { tokens - connection.credentialRef }
        )
        setFailure(connectionId, null)
        if (session != null) {
            scope.launch {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5_000L) {
                        runCatching { provider(connection)?.signOut(session) }
                    }
                }
            }
        }
    }

    fun syncSnapshot(profileId: Int): ServerSyncSnapshot? {
        ensureLoaded()
        if (loadedProfileId != profileId) return null
        val currentTokens = synchronized(lock) { tokens }
        return ServerSyncSnapshot(
            profileId = profileId,
            version = localVersion,
            // Tokenless servers (arrived from another device, not yet signed in here) stay in the set, or the
            // next push would delete them for every device.
            servers = mutableState.value.connections.map { connection ->
                connection.toSynced(currentTokens[connection.credentialRef].orEmpty())
            },
            pendingPush = pendingPush,
            syncedKeys = syncedKeys
        )
    }

    fun applySync(snapshot: ServerSyncSnapshot, servers: List<SyncedServer>, keys: Set<String>): Boolean {
        if (loadedProfileId != snapshot.profileId || localVersion != snapshot.version) return false
        val current = mutableState.value.connections
        val currentByKey = current.associateBy { serverKey(it.providerId, it.remoteServerId, it.remoteUserId) }
        val currentTokens = synchronized(lock) { tokens }
        val usedIds = mutableSetOf<String>()
        val updatedTokens = mutableMapOf<String, String>()
        val connections = servers.map { server ->
            val local = currentByKey[server.key]
            val credentialRef = local?.credentialRef ?: newId("k")
            val id = local?.id ?: server.id.takeUnless { id -> current.any { it.id == id } || id in usedIds } ?: newId("c")
            usedIds += id
            val token = server.token.ifBlank { currentTokens[credentialRef].orEmpty() }
            if (token.isNotBlank()) {
                updatedTokens[credentialRef] = token
                if (local != null && currentTokens[credentialRef] != token) setFailure(local.id, null)
            } else {
                // No token on this device: the existing "Sign in again" state, not a silent failure.
                setFailure(id, ServerFailure.AUTH_REQUIRED)
            }
            server.toConnection(id, credentialRef)
        }
        (current.map { it.id } - usedIds).forEach { setFailure(it, null) }
        pendingPush = false
        syncedKeys = keys
        if (connections == current && updatedTokens == currentTokens) {
            persist(current, currentTokens)
        } else {
            save(connections, updatedTokens, local = false)
        }
        return true
    }

    fun markPushed(snapshot: ServerSyncSnapshot) {
        if (loadedProfileId != snapshot.profileId) return
        syncedKeys = snapshot.servers.mapTo(mutableSetOf()) { it.key }
        if (localVersion == snapshot.version) pendingPush = false
        persist(mutableState.value.connections, synchronized(lock) { tokens })
    }

    override fun removeProfile(profileId: Int) {
        runCatching { persistence.write(profileId, null) }
            .onFailure { Log.w(TAG, "Unable to remove server connections", it) }
        if (loadedProfileId == profileId) load(profileId)
    }

    override fun swapProfiles(a: Int, b: Int) {
        // Credentials are keyed by profile id: exchange the two stored blobs ("make this my main profile").
        runCatching {
            val first = persistence.read(a)
            val second = persistence.read(b)
            persistence.write(a, second)
            persistence.write(b, first)
        }.onFailure { Log.w(TAG, "Unable to swap server connections", it) }
        if (loadedProfileId == a || loadedProfileId == b) load(loadedProfileId ?: return)
    }

    /** The user accepted the fingerprint shown for [error]: pin it for that exact host:port (this device only). */
    fun trustCertificate(error: ServerCertificateException) {
        trust?.pin(error.authority, error.fingerprint)
        mutableState.update { state -> state.copy(failures = state.failures.filterValues { it != ServerFailure.CERTIFICATE_UNTRUSTED && it != ServerFailure.CERTIFICATE_CHANGED }) }
    }

    override fun clearAllProfiles() {
        synchronized(lock) {
            libraryLoadEpoch++
            trust?.clearAll()
            generation++
            synchronized(lock) { tokens = emptyMap() }
            loadedProfileId = null
            pendingPush = false
            syncedKeys = null
            runCatching { persistence.clear() }.onFailure { Log.w(TAG, "Unable to clear server storage", it) }
            mutableState.value = ServersUiState(revision = mutableState.value.revision + 1)
        }
    }

    @Volatile
    private var libraryLoadEpoch = 0L

    internal suspend fun awaitLibraries(connectionId: String) {
        val epoch = libraryLoadEpoch
        val waiting = connectionId in uiState.value.loadingLibraries
        val state = if (waiting) uiState.first { connectionId !in it.loadingLibraries } else uiState.value
        if (epoch != libraryLoadEpoch) throw CancellationException("Server scope changed")
        val failure = state.failures[connectionId]
        if (failure != null && (waiting || state.connections.firstOrNull { it.id == connectionId }?.libraries.isNullOrEmpty() ||
                failure == ServerFailure.AUTH_REQUIRED || failure == ServerFailure.CERTIFICATE_CHANGED || failure == ServerFailure.CERTIFICATE_UNTRUSTED)) {
            throw ServerException(failure)
        }
    }

    private fun load(profileId: Int) {
        synchronized(lock) {
            libraryLoadEpoch++
            generation++
            val stored = readStored(profileId)
            synchronized(lock) { tokens = stored.tokens }
            pendingPush = stored.pendingPush
            syncedKeys = stored.syncedKeys?.toSet()
            libraryRefreshPending = stored.libraryRefreshPending
            loadedProfileId = profileId
            mutableState.value = ServersUiState(
                connections = stored.connections,
                loadingLibraries = if (libraryRefreshPending) stored.connections.filter {
                    it.enabled && !stored.tokens[it.credentialRef].isNullOrBlank()
                }.mapTo(mutableSetOf()) { it.id } else emptySet(),
                // A server that has no token on this device (synced from another one) is a sign-in, not a mystery.
                failures = stored.connections
                    .filter { stored.tokens[it.credentialRef].isNullOrBlank() }
                    .associate { it.id to ServerFailure.AUTH_REQUIRED },
                revision = mutableState.value.revision + 1
            )
            if (libraryRefreshPending) scheduleLibraryRefresh(profileId)
        }
    }

    /**
     * Servers imported from the old fork stack have no libraries yet: fetch them once (the sign-in screen does this for a
     * new server). One pass per load, never a loop; a server that cannot be reached keeps "Refresh libraries".
     */
    private fun scheduleLibraryRefresh(profileId: Int) {
        val epoch = libraryLoadEpoch
        val ids = mutableState.value.loadingLibraries.toList()
        scope.launch {
            ids.forEach { id ->
                if (epoch != libraryLoadEpoch) return@launch
                try {
                    refreshLibraries(id)
                } catch (error: CancellationException) {
                    if (!isActive) throw error
                } catch (error: Exception) {
                    setFailure(id, error.serverFailure(), expectedEpoch = epoch)
                } finally {
                    mutableState.update { state ->
                        if (epoch == libraryLoadEpoch) state.copy(loadingLibraries = state.loadingLibraries - id) else state
                    }
                }
            }
            synchronized(lock) {
                if (loadedProfileId == profileId && epoch == libraryLoadEpoch) {
                    libraryRefreshPending = false
                    persist(mutableState.value.connections, tokens)
                }
            }
        }
    }

    private fun updateConnection(connectionId: String, expectedEpoch: Long? = null, transform: (ServerConnection) -> ServerConnection) {
        synchronized(lock) {
            ensureLoaded()
            if (expectedEpoch != null && expectedEpoch != libraryLoadEpoch) return
            val connections = mutableState.value.connections.map { if (it.id == connectionId) transform(it) else it }
            save(connections, synchronized(lock) { tokens })
        }
    }

    private fun save(connections: List<ServerConnection>, updatedTokens: Map<String, String>, local: Boolean = true) {
        synchronized(lock) {
            val profileId = loadedProfileId ?: return
            val retained = updatedTokens.filterKeys { ref -> connections.any { it.credentialRef == ref } }
            if (local) {
                pendingPush = true
                localVersion++
            }
            persist(connections, retained)
            synchronized(lock) { tokens = retained }
            generation++
            mutableState.update { it.copy(connections = connections, revision = it.revision + 1) }
            if (local) mutableLocalChanges.tryEmit(profileId)
        }
    }

    private fun persist(connections: List<ServerConnection>, currentTokens: Map<String, String>) {
        val profileId = loadedProfileId ?: return
        val stored = StoredServers(connections, currentTokens, pendingPush, syncedKeys?.toList(), libraryRefreshPending)
        runCatching {
            persistence.write(profileId, json.encodeToString(StoredServers.serializer(), stored))
        }.onFailure { Log.w(TAG, "Unable to save server connections", it) }
    }

    private fun setFailure(
        connectionId: String,
        failure: ServerFailure?,
        expectedEpoch: Long? = null,
        expectedConnection: ServerConnection? = null
    ) {
        mutableState.update { state ->
            if (expectedEpoch != null && expectedEpoch != libraryLoadEpoch) return@update state
            if (expectedConnection != null && state.connections.firstOrNull { it.id == connectionId } != expectedConnection) return@update state
            if (state.failures[connectionId] == failure) return@update state
            val failures = if (failure == null) state.failures - connectionId else state.failures + (connectionId to failure)
            state.copy(failures = failures)
        }
    }

    private fun readStored(profileId: Int): StoredServers =
        runCatching {
            persistence.read(profileId)?.let { json.decodeFromString(StoredServers.serializer(), it) }
        }.getOrNull() ?: StoredServers()

    private fun mergeLibraries(previous: List<ServerLibrary>, current: List<ServerLibrary>): List<ServerLibrary> {
        val selection = previous.associate { it.id to it.selected }
        return current.map { library -> library.copy(selected = selection[library.id] ?: true) }
    }

    private fun newId(prefix: String): String =
        prefix + (0 until 15).joinToString("") { Random.nextInt(16).toString(16) }

    private companion object {
        const val TAG = "ServerRepository"
    }
}
