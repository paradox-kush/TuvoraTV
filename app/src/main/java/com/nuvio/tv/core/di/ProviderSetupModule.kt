package com.nuvio.tv.core.di

import com.nuvio.tv.core.iptv.ProviderSetupApi
import com.nuvio.tv.core.iptv.SupabaseProviderSetupApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/** Step 2: the provider-setup client behind its interface (UI and tests use fakes). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderSetupModule {
    @Binds
    @Singleton
    abstract fun bindProviderSetupApi(impl: SupabaseProviderSetupApi): ProviderSetupApi

    /**
     * The managed-playlist cache holds provider names and contact handles: it is account data, so it rides the
     * profile-scoped store set (cleared on sign-out / account deletion, dropped on profile delete, swapped on promote).
     */
    @Binds
    @IntoSet
    abstract fun bindManagedInfoProfileScopedStore(store: com.nuvio.tv.core.iptv.ManagedInfoStore): com.nuvio.tv.core.profile.ProfileScopedCredentialStore
}
