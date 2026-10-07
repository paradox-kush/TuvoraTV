package com.nuvio.tv.core.mediaserver.flow

import com.nuvio.tv.core.mediaserver.api.MediaServerEntry
import com.nuvio.tv.core.mediaserver.api.MediaServerType
import com.nuvio.tv.core.mediaserver.MediaServerAccounts
import com.nuvio.tv.core.mediaserver.SignInResult
import com.nuvio.tv.core.mediaserver.client.AuthSession
import com.nuvio.tv.core.mediaserver.client.DiscoveryResult
import com.nuvio.tv.core.mediaserver.client.MediaServerException
import com.nuvio.tv.core.mediaserver.client.MediaServerServices
import com.nuvio.tv.core.mediaserver.client.MediaServerTrust
import com.nuvio.tv.core.mediaserver.client.PublicUser
import com.nuvio.tv.core.mediaserver.client.QuickConnectOutcome
import com.nuvio.tv.core.mediaserver.client.ServerInfo
import com.nuvio.tv.core.mediaserver.policy.QuickConnectPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class AddStage { ADDRESS, CHOOSE_SIGN_IN, QUICK_CONNECT, PASSWORD, DONE }

internal enum class AddError {
    /** The address field is empty or cannot be an address at all. */
    INVALID_ADDRESS,

    /** Something answered, but it is not a Jellyfin / Emby server (a router page, a login proxy). */
    NOT_A_MEDIA_SERVER,

    /** Nothing answered (wrong host/port, server off, no network). */
    UNREACHABLE,

    /** Wrong name or password. */
    WRONG_CREDENTIALS,

    /** Quick Connect could not finish (disabled on the server, server error, gave up after repeated expiries). */
    QUICK_CONNECT_FAILED,

    /** Sign-in worked but the server could not be reached for the final step / other failure. */
    SIGN_IN_FAILED,

    /** The address now answers as a DIFFERENT server than the entry being signed in (its own id changed). */
    DIFFERENT_SERVER,

    /** The sign-in could not be stored on this device. */
    NOT_SAVED,

    /** The server's ids cannot be turned into a sync key. */
    UNUSABLE_SERVER,
}

/** A certificate the system did not trust: shown with its fingerprint so the user can decide (trust on first use). */
internal data class CertPrompt(val authority: String, val kind: String, val fingerprint: String)

internal data class FoundServer(val baseUrl: String, val info: ServerInfo, val type: MediaServerType)

internal data class QuickConnectView(val displayCode: String, val startedAtMs: Long)

internal data class AddServerState(
    val stage: AddStage = AddStage.ADDRESS,
    val address: String = "",
    /** The product the user picked; null = detect. Fixed when signing in an existing entry. */
    val selectedType: MediaServerType? = null,
    val busy: Boolean = false,
    val error: AddError? = null,
    val certPrompt: CertPrompt? = null,
    val found: FoundServer? = null,
    /** The user picked X but the server says it is Y: surfaced once, the flow continues with Y. */
    val typeCorrectedFrom: MediaServerType? = null,
    val quickConnectAvailable: Boolean = false,
    val publicUsers: List<PublicUser> = emptyList(),
    val quickConnect: QuickConnectView? = null,
    val signedIn: MediaServerEntry? = null,
    /** Signing in an entry that already exists on this account (arrived from another device) rather than adding one. */
    val signingInExisting: Boolean = false,
)

/**
 * The add-a-server / sign-in-on-this-device flow as a state machine (design 5.3/5.4). No Compose and no I/O of its
 * own: it drives [MediaServerServices] / [MediaServerAccounts] / [MediaServerTrust] and publishes [state]; the screen
 * only renders it and forwards taps. The typed PASSWORD is a parameter of [submitPassword] and is never stored here
 * (the client uses it once; only the token it returns is saved, by the secure store).
 *
 * [existing] = sign this device in to an entry that synced from another device: the product is fixed, the address is
 * prefilled when the entry carries one, and [MediaServerAccounts.signIn] (which refuses a DIFFERENT server) is used
 * instead of `addServer`.
 */
internal class AddServerController(
    private val services: MediaServerServices,
    private val accounts: MediaServerAccounts,
    private val trust: MediaServerTrust,
    private val scope: CoroutineScope,
    private val existing: MediaServerEntry? = null,
    /** Told after a sign-in is saved, so Home / search / the list refresh (never part of the flow's logic). */
    private val onSignedIn: (MediaServerEntry) -> Unit = {},
) {
    private val mutable = MutableStateFlow(
        AddServerState(
            address = existing?.address.orEmpty(),
            selectedType = existing?.type,
            signingInExisting = existing != null,
        ),
    )
    val state: StateFlow<AddServerState> = mutable.asStateFlow()

    private var work: Job? = null

    fun setAddress(text: String) = mutable.update { it.copy(address = text, error = null, certPrompt = null) }

    fun selectType(type: MediaServerType?) {
        if (existing != null) return // the product of an existing entry is not negotiable
        mutable.update { it.copy(selectedType = type, error = null) }
    }

    /** Checks the typed address: derives the candidate URLs, probes them, and moves to the sign-in choice on a hit. */
    fun connect() {
        val current = mutable.value
        if (current.busy) return
        if (current.address.isBlank()) {
            mutable.update { it.copy(error = AddError.INVALID_ADDRESS) }
            return
        }
        mutable.update { it.copy(busy = true, error = null, certPrompt = null, typeCorrectedFrom = null) }
        work?.cancel()
        work = scope.launch {
            when (val result = services.discover(current.address, current.selectedType)) {
                is DiscoveryResult.Found -> onFound(result)
                is DiscoveryResult.CertificateNeedsTrust -> mutable.update {
                    it.copy(busy = false, certPrompt = CertPrompt(result.failure.authority, result.failure.failure, result.failure.fingerprint))
                }
                DiscoveryResult.NotAMediaServer -> mutable.update { it.copy(busy = false, error = AddError.NOT_A_MEDIA_SERVER) }
                DiscoveryResult.Unreachable -> mutable.update { it.copy(busy = false, error = AddError.UNREACHABLE) }
                is DiscoveryResult.Rejected -> mutable.update { it.copy(busy = false, error = AddError.INVALID_ADDRESS) }
            }
        }
    }

    private suspend fun onFound(result: DiscoveryResult.Found) {
        val api = services.authApi(result.baseUrl, result.type)
        // Both lookups are anonymous and cheap; a failure of either just means "not offered".
        val (quickConnect, users) = kotlinx.coroutines.coroutineScope {
            val q = async { api.quickConnectEnabled() }
            val u = async { api.publicUsers() }
            q.await() to u.await()
        }
        mutable.update {
            it.copy(
                busy = false,
                stage = AddStage.CHOOSE_SIGN_IN,
                found = FoundServer(result.baseUrl, result.info, result.type),
                typeCorrectedFrom = if (result.typeMismatch) it.selectedType else null,
                selectedType = result.type,
                quickConnectAvailable = quickConnect,
                publicUsers = users,
            )
        }
    }

    /** The user accepted the certificate shown in [AddServerState.certPrompt]: pin it, then retry the address. */
    fun trustCertificate() {
        val prompt = mutable.value.certPrompt ?: return
        trust.pin(prompt.authority, prompt.fingerprint)
        mutable.update { it.copy(certPrompt = null) }
        connect()
    }

    fun declineCertificate() = mutable.update { it.copy(certPrompt = null) }

    /** Moves to the code screen. The polling itself is [runQuickConnect], run by the screen under its own lifecycle. */
    fun startQuickConnect() {
        if (mutable.value.found == null || !mutable.value.quickConnectAvailable) return
        work?.cancel()
        mutable.update { it.copy(stage = AddStage.QUICK_CONNECT, error = null, quickConnect = null) }
    }

    /**
     * The Quick Connect sign-in: asks the server for a code, publishes it in [AddServerState.quickConnect], and polls
     * until someone approves it (then signs in), regenerating the code when the server's request lapses. Suspends for
     * as long as it runs and is cancelled by whoever called it - the screen runs it under `repeatOnLifecycle(RESUMED)`
     * so nothing polls while the app is in the background or the screen is gone (CLAUDE.md recurring-network rule).
     */
    suspend fun runQuickConnect() {
        val found = mutable.value.found ?: return
        if (mutable.value.stage != AddStage.QUICK_CONNECT) return
        repeat(MAX_AUTOMATIC_CODES) {
            when (val outcome = services.signInWithQuickConnect(found.baseUrl, found.type) { request, startedAt ->
                mutable.update { s -> s.copy(quickConnect = QuickConnectView(QuickConnectPolicy.displayCode(request.code), startedAt)) }
            }) {
                is QuickConnectOutcome.SignedIn -> {
                    finish(outcome.session)
                    return
                }
                QuickConnectOutcome.Expired -> Unit // the server's request lapsed unapproved: ask for a fresh code
                QuickConnectOutcome.Failed -> {
                    mutable.update { it.copy(stage = AddStage.CHOOSE_SIGN_IN, quickConnect = null, error = AddError.QUICK_CONNECT_FAILED) }
                    return
                }
            }
        }
        mutable.update { it.copy(stage = AddStage.CHOOSE_SIGN_IN, quickConnect = null, error = AddError.QUICK_CONNECT_FAILED) }
    }

    fun usePassword() {
        if (mutable.value.found == null) return
        work?.cancel()
        mutable.update { it.copy(stage = AddStage.PASSWORD, error = null, quickConnect = null, busy = false) }
    }

    /** Back from the code / password step to the choice (cancels a running Quick Connect). */
    fun backToChoice() {
        work?.cancel()
        mutable.update { it.copy(stage = AddStage.CHOOSE_SIGN_IN, error = null, quickConnect = null, busy = false) }
    }

    /** Back from the choice to the address (e.g. a wrong server). */
    fun backToAddress() {
        work?.cancel()
        mutable.update { it.copy(stage = AddStage.ADDRESS, found = null, error = null, busy = false, quickConnect = null) }
    }

    /** [password] is used once and not kept. */
    fun submitPassword(username: String, password: String) {
        val found = mutable.value.found ?: return
        if (mutable.value.busy) return
        if (username.isBlank()) {
            mutable.update { it.copy(error = AddError.WRONG_CREDENTIALS) }
            return
        }
        mutable.update { it.copy(busy = true, error = null) }
        work?.cancel()
        work = scope.launch {
            try {
                finish(services.authApi(found.baseUrl, found.type).authenticateByName(username.trim(), password))
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaServerException.Http) {
                mutable.update { it.copy(busy = false, error = if (e.isUnauthorized || e.status == 400) AddError.WRONG_CREDENTIALS else AddError.SIGN_IN_FAILED) }
            } catch (e: MediaServerException) {
                mutable.update { it.copy(busy = false, error = AddError.SIGN_IN_FAILED) }
            }
        }
    }

    /** Leaving the screen: stops any polling. */
    fun cancel() {
        work?.cancel()
        work = null
    }

    private fun finish(session: AuthSession) {
        val found = mutable.value.found ?: return
        val result = existing?.let { accounts.signIn(it, found.baseUrl, found.info.machineId, session) }
            ?: accounts.addServer(found.type, found.baseUrl, found.info, session, displayName = null)
        when (result) {
            is SignInResult.Success -> {
                mutable.update { it.copy(stage = AddStage.DONE, busy = false, signedIn = result.entry, quickConnect = null, error = null) }
                onSignedIn(result.entry)
            }
            SignInResult.DifferentServer -> fail(AddError.DIFFERENT_SERVER)
            SignInResult.NotSaved -> fail(AddError.NOT_SAVED)
            SignInResult.UnusableServerIds -> fail(AddError.UNUSABLE_SERVER)
        }
    }

    private fun fail(error: AddError) =
        mutable.update { it.copy(stage = AddStage.CHOOSE_SIGN_IN, busy = false, quickConnect = null, error = error) }

    private companion object {
        /** A code lives 10 minutes; a person who is still at the screen after ~50 minutes has forgotten about it. */
        const val MAX_AUTOMATIC_CODES = 5
    }
}
