package com.nuvio.tv.core.di

import com.nuvio.tv.core.contracts.LivePlayback
import com.nuvio.tv.ui.screens.iptv.player.IptvLivePlayback
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the neutral [LivePlayback] port to its fork implementation (composition root). */
@Module
@InstallIn(SingletonComponent::class)
abstract class LivePlaybackModule {
    @Binds
    @Singleton
    abstract fun bindLivePlayback(impl: IptvLivePlayback): LivePlayback
}
