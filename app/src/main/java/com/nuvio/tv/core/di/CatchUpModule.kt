package com.nuvio.tv.core.di

import com.nuvio.tv.core.iptv.CatchUpWinnerPrefs
import com.nuvio.tv.core.iptv.CatchUpWinnerStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * [CatchUpWinnerStore] is deliberately a plain class over an injected [CatchUpWinnerStore.Persistence]
 * so its policy — including the preference-voids-the-winner rule — is testable without Android.
 * This is the one binding that gives it real disk.
 */
@Module
@InstallIn(SingletonComponent::class)
object CatchUpModule {

    @Provides
    @Singleton
    fun provideCatchUpWinnerStore(prefs: CatchUpWinnerPrefs): CatchUpWinnerStore =
        CatchUpWinnerStore(prefs)
}
