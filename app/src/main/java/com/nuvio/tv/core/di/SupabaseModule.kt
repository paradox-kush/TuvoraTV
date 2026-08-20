package com.nuvio.tv.core.di

import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.data.local.ServerConfigurationStore
import com.nuvio.tv.domain.model.ServerConfiguration
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import javax.inject.Singleton

/**
 * Fork: delegates to the switchable [SyncBackendSupabaseProvider] instead of building a second
 * client from BuildConfig, so every upstream consumer (sync services, realtime invalidation)
 * talks to the selected self-hosted backend.
 *
 * ponytail: these are @Singleton snapshots — after a runtime sync-backend switch (debug-only
 * flow) consumers injected this way see the new client only on app restart; the fork's own
 * services keep resolving live through the provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(provider: SyncBackendSupabaseProvider): SupabaseClient = provider.client

    @Provides
    @Singleton
    fun provideSupabaseAuth(provider: SyncBackendSupabaseProvider): Auth = provider.auth

    @Provides
    @Singleton
    fun provideSupabasePostgrest(provider: SyncBackendSupabaseProvider): Postgrest = provider.postgrest

    // Backs upstream's cosmetic ProfileBackgroundRepository. Kept inert (no member backend) but the
    // binding must exist for the Hilt graph to validate. Resolves through the switchable client.
    @Provides
    @Singleton
    fun provideSupabaseStorage(provider: SyncBackendSupabaseProvider): Storage = provider.client.storage

    // Backs upstream's (gated-off) custom-server ServerConnectionViewModel; the fork reads the
    // active server from its own store so the Hilt graph validates even though the UI is inert.
    @Provides
    @Singleton
    fun provideActiveServerConfiguration(
        configurationStore: ServerConfigurationStore
    ): ServerConfiguration = configurationStore.loadActive()
}
