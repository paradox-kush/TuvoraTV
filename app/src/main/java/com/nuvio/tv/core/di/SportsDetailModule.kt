package com.nuvio.tv.core.di

import android.content.Context
import com.nuvio.tv.core.network.SyncBackendSupabaseProvider
import com.nuvio.tv.core.sports.detail.FileMappingPersistence
import com.nuvio.tv.core.sports.detail.OkHttpFetcher
import com.nuvio.tv.core.sports.detail.PostHogSportsDetailTelemetry
import com.nuvio.tv.core.sports.detail.SportsDetail
import com.nuvio.tv.core.sports.detail.SportsDetailService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SportsDetailModule {
    @Provides
    @Singleton
    fun provideSportsDetailService(
        @ApplicationContext context: Context,
        client: OkHttpClient,
        backend: SyncBackendSupabaseProvider,
    ): SportsDetailService = SportsDetail.create(
        http = OkHttpFetcher(client),
        functionsBase = { runCatching { backend.selectedBackend.normalizedSupabaseUrl.trimEnd('/') + "/functions/v1" }.getOrNull() },
        persistence = FileMappingPersistence(File(context.filesDir, "sports")),
        telemetry = PostHogSportsDetailTelemetry(),
    )
}
