package com.nuvio.tv.core.di

import com.nuvio.tv.core.contracts.RealtimeSyncParticipant
import com.nuvio.tv.core.iptv.overlay.IptvOverlayRealtimeParticipant
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/** Binds fork features' Realtime surface pulls into the neutral participant set (composition root). */
@Module
@InstallIn(SingletonComponent::class)
abstract class RealtimeSyncModule {
    @Multibinds
    abstract fun realtimeSyncParticipants(): Set<RealtimeSyncParticipant>

    @Binds
    @IntoSet
    abstract fun bindIptvOverlayRealtimeParticipant(impl: IptvOverlayRealtimeParticipant): RealtimeSyncParticipant

    @Binds
    @IntoSet
    abstract fun bindIptvPlaylistsRealtimeParticipant(impl: com.nuvio.tv.core.iptv.IptvPlaylistsRealtimeParticipant): RealtimeSyncParticipant

    @Binds
    @IntoSet
    abstract fun bindRadarRealtimeParticipant(impl: com.nuvio.tv.core.radar.RadarRealtimeParticipant): RealtimeSyncParticipant
}
