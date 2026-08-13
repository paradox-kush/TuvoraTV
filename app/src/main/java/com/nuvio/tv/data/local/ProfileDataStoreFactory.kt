package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.domain.model.DiscoverLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

private class ScopedDataStore(
    val store: DataStore<Preferences>,
    val scope: CoroutineScope,
    val job: Job
)

internal val discoverLocationMigration = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        legacySearchDiscoverEnabledKey in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        val legacy = mutable[legacySearchDiscoverEnabledKey]
        if (legacy != null && mutable[discoverLocationKey] == null) {
            val rememberedLocation = mutable[lastNonOffDiscoverLocationKey]?.let {
                runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
            }?.takeIf { it != DiscoverLocation.OFF }
            val resolved = if (legacy && rememberedLocation != null) {
                rememberedLocation
            } else {
                DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy)
            }
            mutable[discoverLocationKey] = resolved.name
        }
        mutable.remove(legacySearchDiscoverEnabledKey)
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, ScopedDataStore>()
    private val deletedProfileIds = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()
    private val retainedStandaloneDataStoreNames = setOf(
        "app_onboarding",
        "auth_session_notice_store",
        "debug_settings",
        "device_local_player_prefs",
        "profile_lock_state",
        "profile_settings",
        "sentry_settings",
        "torrent_settings",
        "tv_channel_prefs"
    )

    /** Set of DataStore file names that were reset due to corruption during this session. */
    val corruptedFileNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p$profileId"
        synchronized(lock) {
            cache[fileName]?.let { return it.store }
            return createAndCache(fileName).store
        }
    }

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        deletedProfileIds.add(profileId)
        val suffix = "_p$profileId"
        val keysToRemove = synchronized(lock) {
            cache.keys.filter { it.endsWith(suffix) }
        }
        for (key in keysToRemove) {
            val scoped = synchronized(lock) { cache.remove(key) } ?: continue
            runCatching { scoped.store.edit { it.clear() } }
            // Cancel the scope so DataStore releases the file from its active-files
            // registry. Wait for completion before any subsequent get() can create
            // a fresh DataStore for the same path.
            scoped.job.cancel()
            scoped.job.join()
        }
    }

    /**
     * Exchange the on-disk DataStore files of two profiles, for "make this my main profile".
     *
     * Profile 1's files are unsuffixed and everyone else's carry `_p<id>`, so a promotion is a
     * physical rename in both directions, via a temp name because the two sets collide.
     *
     * Every affected DataStore is torn down first: DataStore keeps a process-wide registry of
     * active files and a single-writer lock per path, so renaming underneath a live instance
     * corrupts it. Tearing down is necessary but NOT sufficient — anything already holding a
     * DataStore reference keeps the stale object. The caller must restart the UI afterwards
     * (ProfileManager.promoteToPrimary documents this contract).
     */
    suspend fun swapProfileFiles(a: Int, b: Int) = withContext(Dispatchers.IO) {
        if (a == b) return@withContext
        val suffixA = if (a == 1) "" else "_p$a"
        val suffixB = if (b == 1) "" else "_p$b"

        // Tear down every cached store belonging to either profile before touching the filesystem.
        val keysToEvict = synchronized(lock) {
            cache.keys.filter { key ->
                val owner = profileIdForDataStoreName(key)
                owner == a || owner == b
            }
        }
        for (key in keysToEvict) {
            val scoped = synchronized(lock) { cache.remove(key) } ?: continue
            scoped.job.cancel()
            scoped.job.join()
        }

        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) return@withContext
        val files = dataStoreDir.listFiles().orEmpty()

        fun featureNameOf(fileName: String, suffix: String): String? {
            if (!fileName.endsWith(".preferences_pb")) return null
            val name = fileName.removeSuffix(".preferences_pb")
            if (!isProfileScopedDataStoreFile(fileName)) return null
            if (profileIdForDataStoreName(name) == null) return null
            return if (suffix.isEmpty()) name.takeIf { profileIdForDataStoreName(it) == 1 }
            else name.removeSuffix(suffix).takeIf { it != name }
        }

        val featuresA = files.mapNotNull { featureNameOf(it.name, suffixA) }.toSet()
        val featuresB = files.mapNotNull { featureNameOf(it.name, suffixB) }.toSet()

        fun fileFor(feature: String, suffix: String) =
            File(dataStoreDir, "$feature$suffix.preferences_pb")

        // A -> temp, B -> A, temp -> B. Same shuffle the SQL swap uses, same reason.
        for (feature in featuresA) {
            val src = fileFor(feature, suffixA)
            if (src.exists()) src.renameTo(File(dataStoreDir, "$feature$suffixA.swap_tmp"))
        }
        for (feature in featuresB) {
            val src = fileFor(feature, suffixB)
            if (src.exists()) src.renameTo(fileFor(feature, suffixA))
        }
        for (feature in featuresA) {
            val tmp = File(dataStoreDir, "$feature$suffixA.swap_tmp")
            if (tmp.exists()) tmp.renameTo(fileFor(feature, suffixB))
        }

        // Promoting a profile un-deletes both indexes as far as this factory is concerned.
        deletedProfileIds.remove(a)
        deletedProfileIds.remove(b)
    }

    /**
     * Which profile a DataStore file belongs to, or null when it is one of the standalone stores
     * that is deliberately not profile-scoped. An unsuffixed profile-scoped name belongs to 1.
     */
    private fun profileIdForDataStoreName(dataStoreName: String): Int? {
        if (dataStoreName in retainedStandaloneDataStoreNames) return null
        val suffixIndex = dataStoreName.lastIndexOf("_p")
        if (suffixIndex < 0) return 1
        val parsed = dataStoreName.substring(suffixIndex + 2).toIntOrNull() ?: return 1
        return parsed
    }

    suspend fun clearProfileScopedData() = withContext(Dispatchers.IO) {
        val cachedStores = synchronized(lock) { cache.toMap() }
        cachedStores.values.forEach { scoped ->
            runCatching { scoped.store.edit { it.clear() } }
        }
        deletedProfileIds.clear()
        corruptedFileNames.clear()

        val cachedFileNames = cachedStores.keys.mapTo(mutableSetOf()) { "$it.preferences_pb" }
        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) return@withContext
        dataStoreDir.listFiles()?.forEach { file ->
            if (file.name !in cachedFileNames && isProfileScopedDataStoreFile(file.name)) {
                file.delete()
            }
        }
    }

    fun isProfileDeleted(profileId: Int): Boolean = profileId in deletedProfileIds

    fun markProfileCreated(profileId: Int) {
        deletedProfileIds.remove(profileId)
    }

    private fun isProfileScopedDataStoreFile(fileName: String): Boolean {
        if (!fileName.endsWith(".preferences_pb")) return false
        val dataStoreName = fileName.removeSuffix(".preferences_pb")
        return dataStoreName !in retainedStandaloneDataStoreNames
    }

    private fun createAndCache(fileName: String): ScopedDataStore {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val migrations = if (fileName == "layout_settings" || fileName.startsWith("layout_settings_p")) {
            listOf(discoverLocationMigration)
        } else {
            emptyList()
        }
        // Ensure the datastore directory exists — prevents ENOENT on first read
        // when a profile-scoped file hasn't been created yet.
        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) {
            dataStoreDir.mkdirs()
        }
        // DataStore 1.1.x (okio-based) can throw FileNotFoundException if the file
        // doesn't exist yet and a race condition occurs. Pre-create an empty file
        // to avoid this edge case (DataStore will overwrite on first write).
        val targetFile = File(dataStoreDir, "$fileName.preferences_pb")
        if (!targetFile.exists()) {
            try { targetFile.createNewFile() } catch (_: Exception) { }
        }
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { ex ->
                Log.e("ProfileDataStoreFactory", "DataStore corrupted ($fileName): ${ex.message} — attempting shadow copy recovery")
                val recovered = recoverFromShadowCopy(fileName)
                if (recovered != null) {
                    Log.i("ProfileDataStoreFactory", "DataStore recovered from shadow copy ($fileName)")
                    recovered
                } else {
                    Log.e("ProfileDataStoreFactory", "DataStore shadow copy unavailable ($fileName) — resetting to empty preferences")
                    corruptedFileNames.add(fileName)
                    emptyPreferences()
                }
            },
            scope = scope,
            migrations = migrations,
            produceFile = { context.preferencesDataStoreFile(fileName) }
        )

        // Wrap store to persist a shadow copy after each successful read.
        // The shadow copy is written once on first data emission (app start),
        // ensuring a consistent backup exists before any corruption can occur.
        val wrappedStore = ShadowCopyDataStore(store, fileName, scope, this)

        val scoped = ScopedDataStore(wrappedStore, scope, job)
        cache[fileName] = scoped
        return scoped
    }

    internal fun writeShadowCopy(fileName: String, preferences: Preferences) {
        val sourceFile = File(File(context.filesDir, "datastore"), "$fileName.preferences_pb")
        val backupFile = File(File(context.filesDir, "datastore"), "$fileName.preferences_pb.bak")
        try {
            if (sourceFile.exists() && sourceFile.length() > 0) {
                sourceFile.copyTo(backupFile, overwrite = true)
            }
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Failed to write shadow copy for $fileName: ${e.message}")
        }
    }

    private fun recoverFromShadowCopy(fileName: String): Preferences? {
        val backupFile = File(File(context.filesDir, "datastore"), "$fileName.preferences_pb.bak")
        if (!backupFile.exists() || backupFile.length() == 0L) return null
        return try {
            val source = backupFile.inputStream().use { input ->
                okio.Buffer().apply { readFrom(input) }
            }
            val preferences = kotlinx.coroutines.runBlocking {
                androidx.datastore.preferences.core.PreferencesSerializer.readFrom(source)
            }
            Log.i("ProfileDataStoreFactory", "Parsed shadow copy for $fileName (${preferences.asMap().size} keys)")
            preferences
        } catch (e: Exception) {
            Log.w("ProfileDataStoreFactory", "Shadow copy recovery failed for $fileName: ${e.message}")
            null
        }
    }
}


/**
 * Thin wrapper around a DataStore that writes a shadow copy of the underlying
 * preferences file after the first successful read and after each edit.
 * This ensures a known-good backup exists for corruption recovery.
 */
private class ShadowCopyDataStore(
    private val delegate: DataStore<Preferences>,
    private val fileName: String,
    private val scope: CoroutineScope,
    private val factory: ProfileDataStoreFactory
) : DataStore<Preferences> {

    @Volatile
    private var shadowWritten = false

    override val data: kotlinx.coroutines.flow.Flow<Preferences>
        get() = delegate.data.also {
            if (!shadowWritten) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val prefs = delegate.data.first()
                        if (prefs != emptyPreferences()) {
                            factory.writeShadowCopy(fileName, prefs)
                            shadowWritten = true
                        }
                    } catch (_: Exception) { }
                }
            }
        }

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val result = delegate.updateData(transform)
        scope.launch(Dispatchers.IO) {
            try {
                factory.writeShadowCopy(fileName, result)
                shadowWritten = true
            } catch (_: Exception) { }
        }
        return result
    }
}
