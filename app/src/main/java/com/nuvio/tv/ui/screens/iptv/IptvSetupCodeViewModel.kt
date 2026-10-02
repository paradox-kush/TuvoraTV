package com.nuvio.tv.ui.screens.iptv

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.iptv.ManagedInfoRefresher
import com.nuvio.tv.core.iptv.ProviderSetupRepository
import com.nuvio.tv.core.iptv.ProviderSupport
import com.nuvio.tv.core.iptv.RedeemFlowResult
import com.nuvio.tv.core.iptv.SetupCode
import com.nuvio.tv.core.iptv.SetupCodeHolder
import com.nuvio.tv.core.iptv.SetupCodeOutcome
import com.nuvio.tv.core.iptv.SetupEntryPolicy
import com.nuvio.tv.core.iptv.SetupMessage
import com.nuvio.tv.core.iptv.SetupPreview
import com.nuvio.tv.core.iptv.SetupWaitPolicy
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.UserProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SetupPhase { ENTRY, CHECKING, NEEDS_SIGN_IN, PREVIEW, ADDING, ADDED_OTHER_PROFILE, NOTHING_ADDED }

data class SetupCodeUiState(
    /** The characters typed so far (alphabet only, no dashes, at most 12). Never persisted. */
    val typed: String = "",
    val phase: SetupPhase = SetupPhase.ENTRY,
    val message: SetupMessage? = null,
    val expiredSupport: ProviderSupport? = null,
    val preview: SetupPreview? = null,
    val profiles: List<UserProfile> = emptyList(),
    val chosenProfileId: Int = 1,
    val signedIn: Boolean = false,
    /** The account the code will be added to ("Adding to <email>"). */
    val accountLabel: String? = null,
    /** Set after a redeem into a profile that is not the active one. */
    val addedProviderName: String? = null,
    val addedProfileName: String? = null,
    /** The shown code was refused by the server: Continue stays off until it is edited. */
    val rejected: Boolean = false,
    /** Bumped when focus should move to the Delete key (after a rejection). */
    val deleteFocusTick: Int = 0,
    /** Bumped when focus should move to Continue (a retryable failure). */
    val continueFocusTick: Int = 0,
    /** Redeem-time `expired`: the provider's contacts (from the preview) and name, to offer "Contact <provider>". */
    val expiredProvider: String? = null,
    /** The phone-redeem wait ended without a result (cap reached). */
    val waitTimedOut: Boolean = false,
    /** The profile the phone-redeem wait watches (the active one). */
    val watchedProfileName: String? = null,
) {
    /** Never prints the code (a logged state would leak it). */
    override fun toString(): String =
        "SetupCodeUiState(phase=$phase, typedLength=${typed.length}, signedIn=$signedIn, rejected=$rejected)"

    val isComplete: Boolean get() = SetupCode.isComplete(typed)
    val continueEnabled: Boolean
        get() = SetupEntryPolicy.continueEnabled(isComplete, phase == SetupPhase.CHECKING, rejected)
}

sealed interface SetupCodeEvent {
    /** A playlist arrived (typed here or redeemed on a phone): leave to the IPTV settings, which opens its details. */
    data object Finished : SetupCodeEvent
}

/**
 * The "Enter setup code" screen. Two routes end the same way: the code typed with the keypad
 * (preview -> choose profile -> redeem -> one pull -> details), or a phone redeeming it on tuvora.co
 * while this screen waits ([waitForPhoneRedeem], bounded by [SetupWaitPolicy]).
 *
 * The typed code lives only in this state (and [SetupCodeHolder] while signed out): never saved, logged,
 * or sent anywhere but the preview/redeem calls; cleared on success and when the screen is left.
 */
@HiltViewModel
class IptvSetupCodeViewModel @Inject constructor(
    private val repository: ProviderSetupRepository,
    private val refresher: ManagedInfoRefresher,
    private val profileManager: ProfileManager,
    private val authManager: AuthManager,
    private val syncService: XtreamAccountSyncService,
    private val detailsRequests: PlaylistDetailsRequests,
) : ViewModel() {

    private val _ui = MutableStateFlow(
        SetupCodeUiState(
            profiles = profileManager.profiles.value,
            chosenProfileId = profileManager.activeProfileId.value,
            watchedProfileName = profileManager.profiles.value.firstOrNull { it.id == profileManager.activeProfileId.value }?.name,
        )
    )
    val ui: StateFlow<SetupCodeUiState> = _ui.asStateFlow()

    private val _events = Channel<SetupCodeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Latched once a character is typed on the TV: the phone-wait is over for this visit. */
    @Volatile var typedOnTv: Boolean = false
        private set

    /** The code lives at most [SetupCodeHolder.TTL_MS] after the last character typed (the holder's own bound). */
    private var expiryJob: Job? = null

    private fun armExpiry() {
        expiryJob?.cancel()
        expiryJob = viewModelScope.launch {
            delay(SetupCodeHolder.TTL_MS)
            expireCode()
        }
    }

    /** The in-memory code expired: forget it everywhere (state, holder), exactly as when the holder clears. */
    internal fun expireCode() {
        val phase = _ui.value.phase
        if (phase == SetupPhase.CHECKING || phase == SetupPhase.ADDING) return
        repository.clearCode()
        _ui.update { it.copy(typed = "", phase = SetupPhase.ENTRY, preview = null, message = null, rejected = false) }
    }

    private var waitStartedAt: Long = -1L
    private var snapshot: Set<String>? = null
    private var snapshotTaken = false

    init {
        viewModelScope.launch {
            authManager.authState.collect { state ->
                val full = state as? AuthState.FullAccount
                _ui.update {
                    it.copy(
                        signedIn = full != null,
                        accountLabel = full?.email?.takeIf { e -> e.isNotBlank() },
                        profiles = profileManager.profiles.value,
                    )
                }
                // Back from sign-in with a code waiting: carry on to the preview.
                if (full != null && _ui.value.phase == SetupPhase.NEEDS_SIGN_IN && _ui.value.isComplete) continueTapped()
            }
        }
    }

    fun type(c: Char) {
        val s = _ui.value
        if (s.phase != SetupPhase.ENTRY) return
        val ch = c.uppercaseChar()
        if (ch !in SetupCode.ALPHABET || s.typed.length >= SetupCode.LENGTH) return
        typedOnTv = true
        _ui.update { it.copy(typed = it.typed + ch, message = null, expiredSupport = null, expiredProvider = null, rejected = false) }
        armExpiry()
    }

    fun backspace() {
        if (_ui.value.phase != SetupPhase.ENTRY) return
        _ui.update { it.copy(typed = it.typed.dropLast(1), message = null, expiredSupport = null, rejected = false) }
    }

    fun continueTapped() {
        val s = _ui.value
        if (s.phase != SetupPhase.ENTRY && s.phase != SetupPhase.NEEDS_SIGN_IN) return
        if (!s.continueEnabled) return
        _ui.update { it.copy(phase = SetupPhase.CHECKING, message = null) }
        viewModelScope.launch { handlePreview(repository.preview(_ui.value.typed)) }
    }

    private fun handlePreview(outcome: SetupCodeOutcome) {
        _ui.update { s ->
            when (outcome) {
                is SetupCodeOutcome.Ready -> s.copy(
                    phase = SetupPhase.PREVIEW, preview = outcome.preview, message = null,
                    profiles = profileManager.profiles.value,
                    chosenProfileId = profileManager.activeProfileId.value,
                )
                SetupCodeOutcome.NeedsSignIn -> s.copy(phase = SetupPhase.NEEDS_SIGN_IN, message = null)
                else -> s.copy(
                    phase = SetupPhase.ENTRY,
                    message = outcome.message,
                    expiredSupport = (outcome as? SetupCodeOutcome.Expired)?.support,
                    rejected = SetupEntryPolicy.isRejection(outcome),
                    deleteFocusTick = s.deleteFocusTick + if (SetupEntryPolicy.focusAfter(outcome) == SetupEntryPolicy.FocusTarget.DELETE) 1 else 0,
                    continueFocusTick = s.continueFocusTick + if (SetupEntryPolicy.focusAfter(outcome) == SetupEntryPolicy.FocusTarget.CONTINUE) 1 else 0,
                )
            }
        }
    }

    fun chooseProfile(id: Int) = _ui.update { it.copy(chosenProfileId = id) }

    fun confirmAdd() {
        val s = _ui.value
        if (s.phase != SetupPhase.PREVIEW) return
        _ui.update { it.copy(phase = SetupPhase.ADDING, message = null) }
        viewModelScope.launch {
            when (val r = repository.redeem(s.typed, s.chosenProfileId)) {
                is RedeemFlowResult.Added -> {
                    val provider = r.providerName ?: s.preview?.providerName
                    if (r.inActiveProfile && r.playlistKey != null) {
                        detailsRequests.open(r.playlistKey, provider)
                        _ui.update { it.copy(typed = "") }
                        _events.send(SetupCodeEvent.Finished)
                    } else {
                        // TV syncs the active profile only: the other one is pulled when it is switched to.
                        val profileName = _ui.value.profiles.firstOrNull { p -> p.id == s.chosenProfileId }?.name
                        _ui.update {
                            it.copy(
                                typed = "", phase = SetupPhase.ADDED_OTHER_PROFILE,
                                addedProviderName = provider, addedProfileName = profileName,
                            )
                        }
                    }
                }
                is RedeemFlowResult.NothingAdded -> _ui.update {
                    it.copy(
                        typed = "", phase = SetupPhase.NOTHING_ADDED,
                        message = when (r.kind) {
                            com.nuvio.tv.core.iptv.RedeemResultPolicy.Kind.NOTHING_NO_LOGIN -> SetupMessage.NOTHING_NO_LOGIN
                            com.nuvio.tv.core.iptv.RedeemResultPolicy.Kind.NOTHING_BAD_ADDRESS -> SetupMessage.NOTHING_BAD_ADDRESS
                            com.nuvio.tv.core.iptv.RedeemResultPolicy.Kind.ALREADY_SET_UP -> SetupMessage.ALREADY_SET_UP
                            else -> SetupMessage.NOTHING_ADDED
                        },
                    )
                }
                is RedeemFlowResult.Failed -> _ui.update { st ->
                    val outcome = (r.outcome as? SetupCodeOutcome.Expired)
                        ?.let { SetupCodeOutcome.Expired(it.support ?: st.preview?.support) } ?: r.outcome
                    when (outcome) {
                        SetupCodeOutcome.ProfileNotFound -> st.copy(
                            phase = SetupPhase.PREVIEW, message = outcome.message,
                            profiles = profileManager.profiles.value,
                            chosenProfileId = profileManager.activeProfileId.value,
                        )
                        SetupCodeOutcome.NeedsSignIn -> st.copy(phase = SetupPhase.NEEDS_SIGN_IN)
                        else -> st.copy(
                            phase = SetupPhase.ENTRY, message = outcome.message,
                            typed = if (outcome is SetupCodeOutcome.Network || outcome is SetupCodeOutcome.RateLimited) st.typed else "",
                            expiredSupport = (outcome as? SetupCodeOutcome.Expired)?.support?.takeUnless { it.isEmpty },
                            expiredProvider = if (outcome is SetupCodeOutcome.Expired) st.preview?.providerName else null,
                            rejected = SetupEntryPolicy.isRejection(outcome),
                            deleteFocusTick = st.deleteFocusTick + if (SetupEntryPolicy.focusAfter(outcome) == SetupEntryPolicy.FocusTarget.DELETE) 1 else 0,
                        )
                    }
                }
            }
        }
    }

    fun useDifferentCode() {
        repository.clearCode()
        _ui.update { it.copy(typed = "", phase = SetupPhase.ENTRY, preview = null, message = null, expiredSupport = null) }
    }

    /**
     * The "finishes by itself" wait (decision 6.4). Called under `repeatOnLifecycle(RESUMED)`; returns
     * when a playlist appeared (after the one forced pull), the cap was reached, or it is cancelled.
     */
    suspend fun waitForPhoneRedeem() {
        if (typedOnTv || !_ui.value.signedIn) return
        val profileId = profileManager.activeProfileId.value
        if (waitStartedAt < 0) waitStartedAt = SystemClock.elapsedRealtime()
        if (!snapshotTaken) {
            snapshot = refresher.fetchKeys(profileId).getOrNull()
            snapshotTaken = snapshot != null
        }
        val outcome = SetupWaitPolicy.run(
            snapshot = snapshot,
            startedAtMs = waitStartedAt,
            now = { SystemClock.elapsedRealtime() },
            onBaseline = { snapshot = it; snapshotTaken = true },
            poll = { refresher.fetchKeys(profileId) },
        )
        when (outcome) {
            is SetupWaitPolicy.Outcome.Found -> {
                // One pull, then straight to the new playlist's details.
                runCatching { syncService.pullAndApply() }
                val key = outcome.newKeys.first()
                val provider = refresher.infosNow(profileId)[key]?.providerName
                detailsRequests.open(key, provider)
                repository.clearCode()
                _events.send(SetupCodeEvent.Finished)
            }
            // The cap ended the wait with nothing: say so instead of leaving a screen that looks alive.
            SetupWaitPolicy.Outcome.TimedOut -> _ui.update { it.copy(waitTimedOut = true) }
        }
    }

    override fun onCleared() {
        // Leaving the screen without a success clears the code (Cancel / Back).
        repository.clearCode()
        expiryJob?.cancel()
        super.onCleared()
    }
}
