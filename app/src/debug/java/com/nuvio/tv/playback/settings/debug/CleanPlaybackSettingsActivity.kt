package com.nuvio.tv.playback.settings.debug

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.playback.android.AndroidRuntimeCapabilityCollector
import com.nuvio.tv.playback.android.FrameworkAndroidCapabilitySource
import com.nuvio.tv.playback.core.ContentType
import com.nuvio.tv.playback.core.PlaybackRequest
import com.nuvio.tv.playback.core.StreamEvidence
import com.nuvio.tv.playback.settings.PlaybackPreferenceRepository
import com.nuvio.tv.playback.settings.PlaybackPreferenceResolutionContext
import com.nuvio.tv.playback.settings.PlaybackPreferenceResolver
import com.nuvio.tv.playback.settings.SharedPreferencesPlaybackPreferenceDocumentStore
import com.nuvio.tv.ui.screens.settings.CleanPlaybackSettingsResolutionSource
import com.nuvio.tv.ui.screens.settings.CleanPlaybackSettingsScreen
import com.nuvio.tv.ui.screens.settings.CleanPlaybackSettingsViewModel
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class CleanPlaybackSettingsActivity : ComponentActivity() {
    @Inject internal lateinit var profileManager: ProfileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = PlaybackPreferenceRepository(
            SharedPreferencesPlaybackPreferenceDocumentStore(applicationContext),
        )
        val resolutionSource = AndroidDebugPlaybackSettingsResolutionSource(applicationContext)
        val viewModel = ViewModelProvider(
            this,
            CleanPlaybackSettingsViewModelFactory(repository, profileManager, resolutionSource),
        )[CleanPlaybackSettingsViewModel::class.java]

        setContent {
            NuvioTheme {
                CleanPlaybackSettingsScreen(
                    viewModel = viewModel,
                    onBackPress = ::finish,
                )
            }
        }
    }
}

private class CleanPlaybackSettingsViewModelFactory(
    private val repository: PlaybackPreferenceRepository,
    private val profileManager: ProfileManager,
    private val resolutionSource: CleanPlaybackSettingsResolutionSource,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == CleanPlaybackSettingsViewModel::class.java) {
            "Unsupported debug settings ViewModel"
        }
        @Suppress("UNCHECKED_CAST")
        return CleanPlaybackSettingsViewModel(
            repository = repository,
            activeProfileIds = profileManager.activeProfileId,
            resolutionSource = resolutionSource,
        ) as T
    }
}

/** Resolves presentation-only effective values from a fresh device snapshot; it opens no media. */
private class AndroidDebugPlaybackSettingsResolutionSource(
    context: Context,
) : CleanPlaybackSettingsResolutionSource {
    private val collector = AndroidRuntimeCapabilityCollector(FrameworkAndroidCapabilitySource(context))
    private val observationSequence = AtomicLong(0)
    private val request = PlaybackRequest(
        url = "https://debug.invalid/clean-playback-settings",
        contentType = ContentType.VOD,
    ).summary()

    override suspend fun resolve(
        profileId: String,
        requested: com.nuvio.tv.playback.settings.CleanPlaybackPreferences,
    ) = withContext(Dispatchers.Default) {
        require(profileId.isNotBlank()) { "Playback preference profile id must not be blank" }
        val capturedAt = System.currentTimeMillis()
        val android = collector.refresh(
            observationSequence = observationSequence.incrementAndGet(),
            capturedAtEpochMs = capturedAt,
        )
        PlaybackPreferenceResolver.resolve(
            requested,
            PlaybackPreferenceResolutionContext(
                request = request,
                evidence = StreamEvidence(),
                capabilities = android.capabilities,
            ),
        )
    }
}
