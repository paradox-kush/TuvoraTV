package com.nuvio.tv.core.di

import com.nuvio.tv.core.iptv.playback.IptvProviderPlaybackResolver
import com.nuvio.tv.playback.core.ProviderPlaybackResolver
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Makes the production IPTV resolver available to the future clean-player composition root. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CleanPlaybackProviderModule {
    @Binds
    @Singleton
    abstract fun bindProviderPlaybackResolver(
        implementation: IptvProviderPlaybackResolver,
    ): ProviderPlaybackResolver
}
