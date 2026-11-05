package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.recordings.api.RecordingsAndroid
import com.nuvio.tv.core.recordings.api.RecordingsPort
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** Recordings: the one [RecordingsPort] of the process. Screens inject it; receivers and the service read RecordingsAccess. */
@Module
@InstallIn(SingletonComponent::class)
object RecordingsModule {
    @Provides
    @Singleton
    fun provideRecordingsPort(
        @ApplicationContext context: Context,
        @Named("m3uIngest") client: OkHttpClient,
    ): RecordingsPort = RecordingsAndroid.create(context, client)
}
