package com.nuvio.tv.core.di

import com.nuvio.tv.core.contracts.HomeLiveRecentsProvider
import com.nuvio.tv.core.iptv.home.IptvHomeLiveRecents
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class HomeLiveRecentsModule {
    @Binds
    abstract fun bindHomeLiveRecents(impl: IptvHomeLiveRecents): HomeLiveRecentsProvider
}
