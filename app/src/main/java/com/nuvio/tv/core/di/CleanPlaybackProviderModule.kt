package com.nuvio.tv.core.di

import com.nuvio.tv.core.iptv.playback.IptvProviderPlaybackResolver
import com.nuvio.tv.core.iptv.dns.PlaylistDns
import com.nuvio.tv.playback.core.ProviderPlaybackResolver
import com.nuvio.tv.playback.media3.ApplicationDnsResolver
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
    abstract fun bindProviderPlaybackResolver(
        implementation: IptvProviderPlaybackResolver,
    ): ProviderPlaybackResolver

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
