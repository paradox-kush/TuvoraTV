package com.nuvio.tv.core.di

import com.nuvio.tv.core.contracts.IptvSubtitleIds
import com.nuvio.tv.core.iptv.IptvSubtitleIdResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the neutral [IptvSubtitleIds] port to its fork implementation (composition root). */
@Module
@InstallIn(SingletonComponent::class)
abstract class IptvSubtitleIdsModule {
    @Binds
    @Singleton
    abstract fun bindIptvSubtitleIds(impl: IptvSubtitleIdResolver): IptvSubtitleIds
}
