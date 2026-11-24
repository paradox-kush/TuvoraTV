package com.nuvio.tv.core.di

import com.nuvio.tv.core.contracts.SportsAlertsHost
import com.nuvio.tv.core.contracts.SportsAlertsPlayingChannels
import com.nuvio.tv.core.contracts.SportsAlertsUi
import com.nuvio.tv.core.radar.alerts.SportsAlertController
import com.nuvio.tv.core.radar.alerts.SportsAlertPlayingChannels
import com.nuvio.tv.ui.screens.radar.SportsAlertsUiImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the fork's in-app sports alerts into the neutral contracts upstream-aligned code uses (composition root). */
@Module
@InstallIn(SingletonComponent::class)
abstract class SportsAlertsModule {
    @Binds abstract fun bindHost(impl: SportsAlertController): SportsAlertsHost
    @Binds abstract fun bindPlayingChannels(impl: SportsAlertPlayingChannels): SportsAlertsPlayingChannels
    @Binds abstract fun bindUi(impl: SportsAlertsUiImpl): SportsAlertsUi
}
