package com.nuvio.tv.core.di

import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
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
}
