package com.nuvio.tv.core.di

import com.nuvio.tv.core.iptv.BoundedLoadTileBounds
import com.nuvio.tv.core.multiview.DecoderCeilingStore
import com.nuvio.tv.core.multiview.DecoderFactsPort
import com.nuvio.tv.core.multiview.MultiviewGridStore
import com.nuvio.tv.core.multiview.MultiviewQueue
import com.nuvio.tv.core.multiview.TileLoadBounds
import com.nuvio.tv.data.local.DecoderCeilingPreferences
import com.nuvio.tv.data.local.MultiviewGridPreferences
import com.nuvio.tv.playback.android.AndroidDecoderFactsSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Multiview wiring: the one "Add to Multiview" queue, the remembered grid, and the tile load bounds. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MultiviewModule {
    @Binds
    @Singleton
    abstract fun bindMultiviewGridStore(implementation: MultiviewGridPreferences): MultiviewGridStore

    @Binds
    @Singleton
    abstract fun bindDecoderCeilingStore(implementation: DecoderCeilingPreferences): DecoderCeilingStore

    @Binds
    @Singleton
    abstract fun bindDecoderFactsPort(implementation: AndroidDecoderFactsSource): DecoderFactsPort

    companion object {
        /** In memory, channel keys only, shared by the guide (which fills it) and the multiview screen (which takes it). */
        @Provides
        @Singleton
        fun provideMultiviewQueue(): MultiviewQueue = MultiviewQueue()

        @Provides
        @Singleton
        fun provideTileLoadBounds(): TileLoadBounds = BoundedLoadTileBounds()
    }
}
