package com.nuvio.tv.data.locallibrary

import android.util.Log
import com.nuvio.tv.data.local.LocalLibraryPreferences
import com.nuvio.tv.data.local.MatchOverrideStore
import com.nuvio.tv.data.locallibrary.match.MediaMatcher
import com.nuvio.tv.data.locallibrary.source.LocalLibrarySourceFactory
import com.nuvio.tv.domain.model.locallibrary.LocalLibrarySourceConfig
import com.nuvio.tv.domain.model.locallibrary.ScannedItem
import com.nuvio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the lifecycle of local library sources: add/remove, persist
 * credentials, run scans, match items to TMDB, and notify the rest of the app
 * via [MetaRepository.clearCache] when the index changes.
 *
 * Scans run on an app-scoped supervisor scope so adding a new source kicks off
 * background work that survives the originating screen leaving the foreground.
 */
@Singleton
class LocalLibraryManager @Inject constructor(
    private val preferences: LocalLibraryPreferences,
    private val credentialStore: LocalLibraryCredentialStore,
    private val index: LocalLibraryIndex,
    private val overrideStore: MatchOverrideStore,
    private val matcher: MediaMatcher,
    private val sourceFactory: LocalLibrarySourceFactory,
    private val metaRepository: MetaRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanJobs = ConcurrentHashMap<String, Job>()
    private val rescanMutex = Mutex()

    private val _scanProgress = MutableStateFlow<Map<String, ScanProgress>>(emptyMap())
    val scanProgress: StateFlow<Map<String, ScanProgress>> = _scanProgress.asStateFlow()

    val sources: StateFlow<List<LocalLibrarySourceConfig>>
        get() = sourcesFlow

    // Materialize the sources Flow into a StateFlow eagerly (replay 1) so UI screens
    // collect synchronously without waiting on DataStore IO.
    private val sourcesFlow: MutableStateFlow<List<LocalLibrarySourceConfig>> = MutableStateFlow(emptyList())

    init {
        scope.launch {
            preferences.sources.collect { sourcesFlow.value = it }
        }
    }

    suspend fun addJellyfin(
        displayName: String,
        url: String,
        username: String,
        password: String
    ): Result<LocalLibrarySourceConfig> = runCatching {
        val id = generateSourceId()
        val config = LocalLibrarySourceConfig(
            id = id,
            displayName = displayName,
            kind = com.nuvio.tv.domain.model.locallibrary.SourceKind.JELLYFIN,
            urlOrPath = url.trimEnd('/'),
            params = if (username.isNotBlank()) mapOf("username" to username) else emptyMap()
        )
        val source = sourceFactory.create(config) as com.nuvio.tv.data.locallibrary.source.JellyfinSource
        source.authenticate(username, password)
            ?: throw IllegalStateException("Jellyfin authentication failed")
        preferences.upsert(config)
        kickoffScan(config)
        config
    }.onFailure { Log.e(TAG, "addJellyfin failed for $url", it) }

    suspend fun addSmb(
        displayName: String,
        url: String,
        username: String?,
        password: String?,
        domain: String?
    ): Result<LocalLibrarySourceConfig> {
        val id = generateSourceId()
        return runCatching {
            val config = LocalLibrarySourceConfig(
                id = id,
                displayName = displayName,
                kind = com.nuvio.tv.domain.model.locallibrary.SourceKind.SMB,
                urlOrPath = url
            )
            if (!username.isNullOrBlank()) credentialStore.putSecret(id, LocalLibraryCredentialStore.Field.SMB_USERNAME, username)
            if (!password.isNullOrBlank()) credentialStore.putSecret(id, LocalLibraryCredentialStore.Field.SMB_PASSWORD, password)
            if (!domain.isNullOrBlank()) credentialStore.putSecret(id, LocalLibraryCredentialStore.Field.SMB_DOMAIN, domain)
            val source = sourceFactory.create(config)
            val test = source.testConnection()
            if (test.isFailure) {
                throw test.exceptionOrNull() ?: IllegalStateException("SMB test failed")
            }
            preferences.upsert(config)
            kickoffScan(config)
            config
        }.onFailure {
            credentialStore.clearSource(id)
            Log.e(TAG, "addSmb failed for $url", it)
        }
    }

    suspend fun addLocalFile(
        displayName: String,
        treeUri: String
    ): Result<LocalLibrarySourceConfig> = runCatching {
        val id = generateSourceId()
        val config = LocalLibrarySourceConfig(
            id = id,
            displayName = displayName,
            kind = com.nuvio.tv.domain.model.locallibrary.SourceKind.LOCAL_FILE,
            urlOrPath = treeUri
        )
        val source = sourceFactory.create(config)
        val test = source.testConnection()
        if (test.isFailure) {
            throw test.exceptionOrNull() ?: IllegalStateException("Folder not accessible")
        }
        preferences.upsert(config)
        kickoffScan(config)
        config
    }.onFailure { Log.e(TAG, "addLocalFile failed for $treeUri", it) }

    /**
     * Verifies a Jellyfin URL + credentials without persisting anything. Used
     * by the Test Connection button in Add Source.
     */
    suspend fun testJellyfin(url: String, username: String, password: String): Result<Unit> {
        if (url.isBlank()) return Result.failure(IllegalArgumentException("URL is required"))
        if (username.isBlank()) return Result.failure(IllegalArgumentException("Username is required"))
        val transientConfig = LocalLibrarySourceConfig(
            id = "test-jellyfin-${System.nanoTime()}",
            displayName = "Test",
            kind = com.nuvio.tv.domain.model.locallibrary.SourceKind.JELLYFIN,
            urlOrPath = url.trimEnd('/')
        )
        val source = sourceFactory.create(transientConfig) as com.nuvio.tv.data.locallibrary.source.JellyfinSource
        return source.verifyCredentials(username, password).map { }
    }

    /**
     * Verifies an SMB share + credentials by connecting and listing the root
     * directory. Credentials are stored under a transient id only for the
     * duration of the test and cleared before returning.
     */
    suspend fun testSmb(
        url: String,
        username: String?,
        password: String?,
        domain: String?
    ): Result<Unit> {
        if (url.isBlank()) return Result.failure(IllegalArgumentException("URL is required"))
        val transientId = "test-smb-${System.nanoTime()}"
        return try {
            if (!username.isNullOrBlank()) credentialStore.putSecret(transientId, LocalLibraryCredentialStore.Field.SMB_USERNAME, username)
            if (!password.isNullOrBlank()) credentialStore.putSecret(transientId, LocalLibraryCredentialStore.Field.SMB_PASSWORD, password)
            if (!domain.isNullOrBlank()) credentialStore.putSecret(transientId, LocalLibraryCredentialStore.Field.SMB_DOMAIN, domain)
            val transientConfig = LocalLibrarySourceConfig(
                id = transientId,
                displayName = "Test",
                kind = com.nuvio.tv.domain.model.locallibrary.SourceKind.SMB,
                urlOrPath = url
            )
            val source = sourceFactory.create(transientConfig)
            source.testConnection().also { result ->
                result.exceptionOrNull()?.let { Log.e(TAG, "testSmb failed for $url", it) }
            }
        } finally {
            credentialStore.clearSource(transientId)
        }
    }

    suspend fun removeSource(sourceId: String) {
        scanJobs[sourceId]?.cancel()
        scanJobs.remove(sourceId)
        preferences.remove(sourceId)
        credentialStore.clearSource(sourceId)
        index.deleteSource(sourceId)
        overrideStore.removeForSource(sourceId)
        metaRepository.clearCache()
    }

    suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        preferences.setEnabled(sourceId, enabled)
        metaRepository.clearCache()
    }

    /** Manually trigger a rescan from the settings UI. */
    fun rescan(sourceId: String) {
        scope.launch {
            val config = preferences.sources.first().firstOrNull { it.id == sourceId } ?: return@launch
            kickoffScan(config)
        }
    }

    /** Rescan every configured source. Scans serialize via [rescanMutex]. */
    fun rescanAll() {
        scope.launch {
            preferences.sources.first().forEach { kickoffScan(it) }
        }
    }

    /** Returns scanned items in the index that don't yet have a TMDB match. */
    suspend fun unmatchedItems(sourceId: String): List<ScannedItem> {
        val items = index.load(sourceId)
        val matches = overrideStore.matches.first()
        return items.filter { matches[it.itemKey] == null }
    }

    suspend fun resolveItem(localId: String): ScannedItem? = index.findByLocalId(localId)

    private fun kickoffScan(config: LocalLibrarySourceConfig) {
        scanJobs[config.id]?.cancel()
        scanJobs[config.id] = scope.launch {
            rescanMutex.withLock {
                runScan(config)
            }
        }
    }

    private suspend fun runScan(config: LocalLibrarySourceConfig) {
        Log.i(TAG, "runScan start sourceId=${config.id} kind=${config.kind} url=${config.urlOrPath}")
        _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Scanning(0))
        val source = sourceFactory.create(config)
        val collected = mutableListOf<ScannedItem>()
        try {
            // Collect incrementally so the UI reflects a live, advancing count
            // instead of sitting on "0 found" for the whole scan. Throttle the
            // StateFlow churn so a large library doesn't spam recompositions.
            source.scan().collect { item ->
                collected += item
                if (collected.size <= 20 || collected.size % 25 == 0) {
                    _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Scanning(collected.size))
                }
            }
            _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Scanning(collected.size))
        } catch (t: Throwable) {
            Log.e(TAG, "Scan failed for ${config.id}", t)
            _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Failed(t.message ?: "Scan failed"))
            return
        }
        Log.i(TAG, "runScan scanned sourceId=${config.id} items=${collected.size}")
        index.replace(config.id, collected)
        _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Matching(0, collected.size))

        var matched = 0
        collected.forEachIndexed { i, item ->
            runCatching { matcher.match(item) }
                .onSuccess { if (it != null) matched++ }
                .onFailure { Log.w(TAG, "Match failed for ${item.itemKey}", it) }
            if (i % 25 == 0 || i == collected.lastIndex) {
                _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Matching(i + 1, collected.size))
            }
        }
        Log.i(TAG, "runScan complete sourceId=${config.id} scanned=${collected.size} matched=$matched")
        preferences.setScanResult(config.id, collected.size, System.currentTimeMillis())
        metaRepository.clearCache()
        _scanProgress.value = _scanProgress.value + (config.id to ScanProgress.Idle(collected.size))
    }

    private fun generateSourceId(): String = java.util.UUID.randomUUID().toString()

    sealed class ScanProgress {
        data class Idle(val itemCount: Int) : ScanProgress()
        data class Scanning(val itemsFound: Int) : ScanProgress()
        data class Matching(val matched: Int, val total: Int) : ScanProgress()
        data class Failed(val reason: String) : ScanProgress()
    }

    companion object {
        private const val TAG = "LocalLibraryManager"
    }
}
