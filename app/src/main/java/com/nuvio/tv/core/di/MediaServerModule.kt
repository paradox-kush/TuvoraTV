package com.nuvio.tv.core.di

import android.content.Context
import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.NoDelaySocketFactory
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.core.sync.SyncClientIdentity
import com.nuvio.tv.data.local.ProfileDataStore
import com.nuvio.tv.data.mediaserver.AndroidLegacyImportMarker
import com.nuvio.tv.data.mediaserver.AndroidLegacyMediaServerSource
import com.nuvio.tv.data.mediaserver.AndroidServerDeviceIdStore
import com.nuvio.tv.data.mediaserver.LegacyMediaServerMigration
import com.nuvio.tv.data.mediaserver.AndroidServerPersistence
import com.nuvio.tv.data.mediaserver.AndroidServerTrustStore
import com.nuvio.tv.data.mediaserver.ServerDeviceId
import com.nuvio.tv.data.mediaserver.ServerRepository
import com.nuvio.tv.data.mediaserver.ServerTrust
import com.nuvio.tv.data.mediaserver.emby.EmbyProvider
import com.nuvio.tv.data.mediaserver.jellyfin.JellyfinProvider
import com.nuvio.tv.data.mediaserver.mediabrowser.ServerClientIdentity
import com.nuvio.tv.data.mediaserver.plex.PlexProvider
import com.nuvio.tv.data.mediaserver.silo.SiloProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object MediaServerModule {
    @Provides
    @Singleton
    fun trust(store: AndroidServerTrustStore): ServerTrust = ServerTrust(store)

    @Provides
    @Singleton
    fun repository(
        @ApplicationContext context: Context,
        persistence: AndroidServerPersistence,
        deviceIdStore: AndroidServerDeviceIdStore,
        trustStore: AndroidServerTrustStore,
        identity: SyncClientIdentity,
        profileDataStore: ProfileDataStore,
        trust: ServerTrust
    ): ServerRepository {
        // Once, before anything reads the store: the servers the previously released fork stack saved on this device.
        val migration = LegacyMediaServerMigration(
            source = AndroidLegacyMediaServerSource(context),
            persistence = persistence,
            trust = trustStore,
            deviceIds = deviceIdStore,
            marker = AndroidLegacyImportMarker(context)
        ).runOnce()
        android.util.Log.i("MediaServerMigration", migration.toString())
        val http = OkHttpClient.Builder()
            .socketFactory(NoDelaySocketFactory)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
        // Chosen once: a device that already signed in keeps the id its tokens are bound to; a new one gets the Tuvora prefix.
        val serverDeviceId = ServerDeviceId(deviceIdStore, identity::currentClientId, persistence::hasAny)
        val clientIdentity = ServerClientIdentity(
            device = Build.MODEL.orEmpty().ifBlank { "Android TV" },
            version = BuildConfig.VERSION_NAME,
            deviceId = serverDeviceId::current
        )
        lateinit var repository: ServerRepository
        val profileClientIdentity = ServerClientIdentity(
            device = clientIdentity.device,
            version = clientIdentity.version,
            deviceId = { repository.profileDeviceId(serverDeviceId.current()) }
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = ServerRepository(
            persistence = persistence,
            providers = listOf(
                JellyfinProvider(http, profileClientIdentity, trust),
                EmbyProvider(http, profileClientIdentity, trust),
                PlexProvider(http, clientIdentity),
                SiloProvider(http, clientIdentity)
            ),
            scope = scope,
            trust = trust
        )
        scope.launch { profileDataStore.activeProfileId.collect(repository::selectProfile) }
        return repository
    }

    @Provides
    @IntoSet
    fun credentialStore(repository: ServerRepository): ProfileScopedCredentialStore = repository
}
