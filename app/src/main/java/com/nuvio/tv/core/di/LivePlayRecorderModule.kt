package com.nuvio.tv.core.di

import com.nuvio.tv.core.contracts.LivePlayRecorder
import com.nuvio.tv.core.iptv.PinSuggestionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the neutral [LivePlayRecorder] port to its fork implementation (composition root). */
@Module
@InstallIn(SingletonComponent::class)
abstract class LivePlayRecorderModule {
    @Binds
    @Singleton
    abstract fun bindLivePlayRecorder(impl: PinSuggestionStore): LivePlayRecorder
}
