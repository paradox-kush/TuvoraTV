// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.di

import com.nuvio.tv.core.calendar.CalendarClock
import com.nuvio.tv.core.calendar.CalendarRefreshInputs
import com.nuvio.tv.core.calendar.CalendarReminderData
import com.nuvio.tv.core.calendar.CalendarRemoteSources
import com.nuvio.tv.core.calendar.CalendarStorage
import com.nuvio.tv.core.calendar.DefaultCalendarRefreshInputs
import com.nuvio.tv.core.calendar.DefaultCalendarReminderData
import com.nuvio.tv.core.calendar.DefaultCalendarRemoteSources
import com.nuvio.tv.core.network.TextCallTimeout
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import com.nuvio.tv.data.remote.api.CalendarTmdbApi
import com.nuvio.tv.data.remote.api.CalendarTraktApi
import com.nuvio.tv.data.remote.api.TvMazeApi
import com.nuvio.tv.ui.screens.calendar.CalendarScreenData
import com.nuvio.tv.ui.screens.calendar.DefaultCalendarScreenData
import com.squareup.moshi.Moshi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.time.Clock
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

@Module
@InstallIn(SingletonComponent::class)
abstract class CalendarModule {
    @Binds
    abstract fun bindCalendarRemoteSources(sources: DefaultCalendarRemoteSources): CalendarRemoteSources

    @Binds
    abstract fun bindCalendarRefreshInputs(inputs: DefaultCalendarRefreshInputs): CalendarRefreshInputs

    @Binds
    abstract fun bindCalendarReminderData(data: DefaultCalendarReminderData): CalendarReminderData

    /** The Calendar's games come from the radar state Sports already holds (no new request, no poll). */
    @Binds
    abstract fun bindCalendarSportsSource(source: com.nuvio.tv.core.radar.RadarCalendarSportsSource): com.nuvio.tv.core.calendar.CalendarSportsSource

    @Binds
    abstract fun bindCalendarScreenData(data: DefaultCalendarScreenData): CalendarScreenData

    @Binds
    @IntoSet
    abstract fun bindCalendarStorage(storage: CalendarStorage): ProfileScopedCredentialStore

    companion object {
        /** TVmaze is first-party-style text JSON: the default client, with the per-call 60 s text limit. */
        @Provides
        @Singleton
        @Named("tvmaze")
        fun provideTvMazeRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
            Retrofit.Builder()
                .baseUrl("https://api.tvmaze.com/")
                .callFactory(TextCallTimeout.callFactory(okHttpClient))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

        @Provides
        @Singleton
        fun provideTvMazeApi(@Named("tvmaze") retrofit: Retrofit): TvMazeApi = retrofit.create(TvMazeApi::class.java)

        @Provides
        @Singleton
        fun provideCalendarTraktApi(@Named("trakt") retrofit: Retrofit): CalendarTraktApi =
            retrofit.create(CalendarTraktApi::class.java)

        @Provides
        @Singleton
        fun provideCalendarTmdbApi(@Named("tmdb") retrofit: Retrofit): CalendarTmdbApi =
            retrofit.create(CalendarTmdbApi::class.java)

        @Provides
        @CalendarClock
        fun provideCalendarClock(): Clock = Clock.systemUTC()
    }
}
