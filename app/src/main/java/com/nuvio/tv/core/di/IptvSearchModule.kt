package com.nuvio.tv.core.di

import com.nuvio.tv.core.contracts.IptvSearchProvider
import com.nuvio.tv.core.iptv.XtreamIptvSearchProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the neutral [IptvSearchProvider] port to its fork implementation (composition root). */
@Module
@InstallIn(SingletonComponent::class)
abstract class IptvSearchModule {
    @Binds
    @Singleton
    abstract fun bindIptvSearchProvider(impl: XtreamIptvSearchProvider): IptvSearchProvider
}
