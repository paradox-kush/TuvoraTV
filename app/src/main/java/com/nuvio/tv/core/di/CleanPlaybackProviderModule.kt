package com.nuvio.tv.core.di

import com.nuvio.tv.core.iptv.playback.IptvProviderPlaybackResolverFactory
import com.nuvio.tv.core.iptv.playback.IptvLiveChannelBridge
import com.nuvio.tv.core.iptv.dns.PlaylistDns
import com.nuvio.tv.playback.core.ProviderPlaybackResolverFactory
import com.nuvio.tv.playback.media3.ApplicationDnsResolver
import com.nuvio.tv.playback.live.LiveChannelNavigationPort
import com.nuvio.tv.playback.live.LiveChannelSelectionPort
import com.nuvio.tv.playback.live.LivePlayedHistoryPort
import com.nuvio.tv.playback.wiring.ActiveProfileLegacyPlaybackPreferenceSnapshotSource
import com.nuvio.tv.playback.wiring.LegacyPlaybackPreferenceSnapshotSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Makes the production IPTV resolver available to the future clean-player composition root. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CleanPlaybackProviderModule {
    @Binds
    @Singleton
    abstract fun bindProviderPlaybackResolverFactory(
        implementation: IptvProviderPlaybackResolverFactory,
    ): ProviderPlaybackResolverFactory

    @Binds
    @Singleton
    abstract fun bindLiveChannelNavigationPort(
        implementation: IptvLiveChannelBridge,
    ): LiveChannelNavigationPort

    @Binds
    @Singleton
    abstract fun bindLiveChannelSelectionPort(
        implementation: IptvLiveChannelBridge,
    ): LiveChannelSelectionPort

    @Binds
    @Singleton
    abstract fun bindLivePlayedHistoryPort(
        implementation: IptvLiveChannelBridge,
    ): LivePlayedHistoryPort

    @Binds
    @Singleton
    internal abstract fun bindLegacyPlaybackPreferenceSnapshotSource(
        implementation: ActiveProfileLegacyPlaybackPreferenceSnapshotSource,
    ): LegacyPlaybackPreferenceSnapshotSource

    companion object {
        @Provides
        @Singleton
        internal fun provideCleanPlaybackApplicationDnsResolver(
            playlistDns: PlaylistDns,
        ): ApplicationDnsResolver = ApplicationDnsResolver { key ->
            key.value.takeIf(playlistDns::usesDoh)?.let(playlistDns::dnsFor)
        }
    }
}
