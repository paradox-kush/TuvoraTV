package com.nuvio.tv.core.di

import com.nuvio.tv.core.contracts.IptvStreamSources
import com.nuvio.tv.core.iptv.XtreamIptvStreamSources
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the neutral [IptvStreamSources] port to its fork implementation (composition root). */
@Module
@InstallIn(SingletonComponent::class)
abstract class IptvStreamSourcesModule {
    @Binds
    @Singleton
    abstract fun bindIptvStreamSources(impl: XtreamIptvStreamSources): IptvStreamSources
}
