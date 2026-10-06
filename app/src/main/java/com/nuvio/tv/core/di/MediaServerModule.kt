package com.nuvio.tv.core.di

import com.nuvio.tv.core.mediaserver.MediaServerAccountCleaner
import com.nuvio.tv.core.profile.ProfileScopedCredentialStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Composition root of the media-server feature: its sign-out / profile-delete cleanup joins the set every other
 * per-profile credential store is already in, so no call is added to the account-reset service.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class MediaServerModule {
    @Binds
    @IntoSet
    abstract fun bindMediaServerAccountCleaner(cleaner: MediaServerAccountCleaner): ProfileScopedCredentialStore
}
