package com.nuvio.tv.core.di

import com.nuvio.tv.core.iptv.ProviderSetupApi
import com.nuvio.tv.core.iptv.SupabaseProviderSetupApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Step 2: the provider-setup client behind its interface (UI and tests use fakes). */
@Module
@InstallIn(SingletonComponent::class)
abstract class ProviderSetupModule {
    @Binds
    @Singleton
    abstract fun bindProviderSetupApi(impl: SupabaseProviderSetupApi): ProviderSetupApi
}
