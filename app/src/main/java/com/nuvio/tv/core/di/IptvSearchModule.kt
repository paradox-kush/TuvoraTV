package com.nuvio.tv.core.di

import com.nuvio.tv.core.contracts.CompositeSearchProvider
import com.nuvio.tv.core.contracts.IptvSearchProvider
import com.nuvio.tv.core.contracts.SearchProviderRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the neutral [IptvSearchProvider] port to the PLURAL view over [SearchProviderRegistry] (composition
 * root). Each own source registers its provider at startup (NuvioApplication); IPTV is one entry, a media
 * server another.
 */
@Module
@InstallIn(SingletonComponent::class)
object IptvSearchModule {
    @Provides
    @Singleton
    fun provideIptvSearchProvider(): IptvSearchProvider = CompositeSearchProvider { SearchProviderRegistry.all }
}
