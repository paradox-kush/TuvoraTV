package com.nuvio.tv.core.profile

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileManager @Inject constructor(
    private val profileDataStore: ProfileDataStore,
    private val factory: ProfileDataStoreFactory,
    private val credentialStores: Set<@JvmSuppressWildcards ProfileScopedCredentialStore>,
    @ApplicationContext private val context: Context
) {
    companion object {
        const val MAX_PROFILES = 6
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeProfileId: StateFlow<Int> = profileDataStore.activeProfileId
        .stateIn(scope, SharingStarted.Eagerly, 1)

    val activeProfileReady: StateFlow<Boolean> = profileDataStore.activeProfileId
        .map { true }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val hasEverSelectedProfile: StateFlow<Boolean> = profileDataStore.hasEverSelectedProfile
        .stateIn(scope, SharingStarted.Eagerly, false)

    val rememberLastProfileEnabled: StateFlow<Boolean> = profileDataStore.rememberLastProfileEnabled
        .stateIn(scope, SharingStarted.Eagerly, false)

    val profiles: StateFlow<List<UserProfile>> = profileDataStore.profilesList
        .stateIn(scope, SharingStarted.Eagerly, listOf(
            UserProfile(id = 1, name = context.getString(R.string.profile_default_name, 1), avatarColorHex = "#1E88E5")
        ))

    val activeProfile: UserProfile?
        get() = profiles.value.find { it.id == activeProfileId.value }

    val isPrimaryProfileActive: Boolean
        get() = activeProfileId.value == 1

    val canCreateProfile: Boolean
        get() = profiles.value.size < MAX_PROFILES

    suspend fun setActiveProfile(id: Int) {
        val exists = profiles.value.any { it.id == id }
        if (exists) {
            profileDataStore.setActiveProfile(id)
        }
    }

    suspend fun setRememberLastProfileEnabled(enabled: Boolean) {
        profileDataStore.setRememberLastProfileEnabled(enabled)
    }

    suspend fun createProfile(
        name: String,
        avatarColorHex: String,
        usesPrimaryAddons: Boolean = false,
        usesPrimaryPlugins: Boolean = false,
        avatarId: String? = null
    ): Boolean {
        val current = profiles.value
        if (current.size >= MAX_PROFILES) return false

        val usedIds = current.map { it.id }.toSet()
        val nextId = (2..MAX_PROFILES).firstOrNull { it !in usedIds } ?: return false

        val profile = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { context.getString(R.string.profile_default_name, nextId) },
            avatarColorHex = avatarColorHex,
            usesPrimaryAddons = usesPrimaryAddons,
            usesPrimaryPlugins = usesPrimaryPlugins,
            avatarId = avatarId
        )
        factory.markProfileCreated(nextId)
        profileDataStore.upsertProfile(profile)
        return true
    }

    suspend fun deleteProfile(id: Int): Boolean {
        if (id == 1) return false
        if (profiles.value.none { it.id == id }) return false
        credentialStores.forEach { store -> store.removeProfile(id) }
        deleteProfileDataAsync(id)
        profileDataStore.deleteProfile(id)
        return true
    }

    /**
     * Swap [id] with the primary profile so it becomes profile 1.
     *
     * Profile 1 is the anchor: it can't be deleted and other profiles inherit its addons/plugins,
     * so "primary" is a position, not a flag. Promoting therefore means exchanging everything the
     * two profiles own — the synced rows (caller's job, via ProfileSyncService.swapProfileData),
     * the on-disk DataStore files, the plugin code directories, and the credential stores.
     *
     * IMPORTANT: the caller must restart the UI afterwards. Evicting the DataStore cache frees the
     * files for renaming, but every already-injected DataStore reference in the graph still points
     * at the old instance. FolderDetailScreen-style hot reuse would read stale data until the
     * process is rebuilt. ProfileSettingsViewModel recreates the activity for this reason.
     */
    suspend fun swapProfileIndexes(id: Int): Boolean {
        if (id == 1) return false
        val current = profiles.value
        if (current.none { it.id == id }) return false
        if (current.none { it.id == 1 }) return false

        swapProfileLocalData(1, id)

        val swapped = current.map { profile ->
            when (profile.id) {
                1 -> profile.copy(id = id)
                id -> profile.copy(id = 1)
                else -> profile
            }
        }.sortedBy { it.id }
        profileDataStore.replaceAllProfiles(swapped)

        // Follow the profile the user is on, not the index — the index now means someone else.
        when (activeProfileId.value) {
            1 -> profileDataStore.setActiveProfile(id)
            id -> profileDataStore.setActiveProfile(1)
        }
        return true
    }

    private suspend fun swapProfileLocalData(a: Int, b: Int) = withContext(Dispatchers.IO) {
        factory.swapProfileFiles(a, b)
        credentialStores.forEach { store -> store.swapProfiles(a, b) }

        // Plugin code follows the same "unsuffixed means profile 1" convention as the DataStores
        // (PluginDataStore: `if (pid == 1) "plugin_code" else "plugin_code_p$pid"`).
        fun pluginDir(id: Int) = File(context.filesDir, if (id == 1) "plugin_code" else "plugin_code_p$id")
        val dirA = pluginDir(a)
        val dirB = pluginDir(b)
        val tmp = File(context.filesDir, "plugin_code_swap_tmp")
        if (tmp.exists()) tmp.deleteRecursively()
        val hadA = dirA.exists()
        val hadB = dirB.exists()
        if (hadA) dirA.renameTo(tmp)
        if (hadB) dirB.renameTo(dirA)
        if (hadA) tmp.renameTo(dirB)
    }

    suspend fun updateProfile(profile: UserProfile): Boolean {
        if (profiles.value.none { it.id == profile.id }) return false
        profileDataStore.upsertProfile(profile)
        return true
    }

    private suspend fun deleteProfileDataAsync(profileId: Int) = withContext(Dispatchers.IO) {
        if (profileId == 1) return@withContext

        factory.clearProfile(profileId)

        val suffixWithExtension = "_p${profileId}.preferences_pb"
        val dataStoreDir = File(context.filesDir, "datastore")
        if (dataStoreDir.exists()) {
            dataStoreDir.listFiles()?.forEach { file ->
                if (file.name.endsWith(suffixWithExtension)) {
                    file.delete()
                }
            }
        }

        val pluginCodeDir = File(context.filesDir, "plugin_code_p${profileId}")
        if (pluginCodeDir.exists()) {
            pluginCodeDir.deleteRecursively()
        }
    }
}
