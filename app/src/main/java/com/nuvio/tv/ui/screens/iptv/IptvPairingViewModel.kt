package com.nuvio.tv.ui.screens.iptv

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.iptv.IptvPairingManager
import com.nuvio.tv.core.iptv.XtreamAccount
import com.nuvio.tv.core.iptv.pairingPayloadToXtreamAccount
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.core.sync.XtreamAccountSyncService
import com.nuvio.tv.data.local.XtreamAccountStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Lifecycle of a pairing session, driving what the pairing screen renders. */
enum class IptvPairingStatus { LOADING, WAITING, SAVING, SUCCESS, EXPIRED, ERROR }

data class IptvPairingUiState(
    val status: IptvPairingStatus = IptvPairingStatus.LOADING,
    val code: String? = null,
    val webUrl: String? = null,
    val qrBitmap: Bitmap? = null,
    val expiresAtMillis: Long? = null,
    /** Name of the just-saved playlist (shown on the success screen). */
    val savedAccountName: String? = null,
    val errorMessage: String? = null
)

/**
 * Drives the P5 "Pair from phone" screen. Mirrors AccountViewModel's QR-login flow (start -> poll at
 * the server interval -> act on the result) but for the anon IPTV pairing RPCs, and instead of a
 * token exchange the terminal action is: map the submitted payload -> [XtreamAccount], persist it,
 * and trigger a sync. Works with NO signed-in session.
 */
@HiltViewModel
class IptvPairingViewModel @Inject constructor(
    private val pairingManager: IptvPairingManager,
    private val accountStore: XtreamAccountStore,
    private val syncService: XtreamAccountSyncService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(IptvPairingUiState())
    val uiState: StateFlow<IptvPairingUiState> = _uiState.asStateFlow()

    private var secret: String? = null
    private var pollIntervalSeconds: Int = 3
    private var pollJob: Job? = null

    init {
        startPairing()
    }

    /** (Re)start a pairing session — used on first show and from the "Try again" retry button. */
    fun startPairing() {
        cancelPolling()
        secret = null
        _uiState.value = IptvPairingUiState(status = IptvPairingStatus.LOADING)
        viewModelScope.launch {
            pairingManager.createPairing(BuildConfig.IPTV_PAIRING_WEB_BASE_URL).fold(
                onSuccess = { pairing ->
                    secret = pairing.secret
                    pollIntervalSeconds = pairing.pollIntervalSeconds
                    val expiresAt = pairing.expiresAtIso?.let {
                        runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                    }
                    val qr = runCatching { QrCodeGenerator.generate(pairing.webUrl, 420, margin = 1) }.getOrNull()
                    _uiState.update {
                        it.copy(
                            status = IptvPairingStatus.WAITING,
                            code = pairing.code,
                            webUrl = pairing.webUrl,
                            qrBitmap = qr,
                            expiresAtMillis = expiresAt,
                            errorMessage = null
                        )
                    }
                    startPolling()
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(status = IptvPairingStatus.ERROR, errorMessage = friendlyError(e))
                    }
                }
            )
        }
    }

    private fun startPolling() {
        cancelPolling()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(pollIntervalSeconds.coerceAtLeast(2) * 1000L)
                pollOnce()
                // pollOnce may cancel this job (success/expired); the isActive check guards the loop.
            }
        }
    }

    private suspend fun pollOnce() {
        val code = _uiState.value.code ?: return
        val secret = this.secret ?: return
        pairingManager.pollOnce(code, secret).fold(
            onSuccess = { result ->
                val expiresAt = result.expiresAt?.let {
                    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                }
                result.pollIntervalSeconds?.let { pollIntervalSeconds = it.coerceAtLeast(2) }
                if (expiresAt != null) _uiState.update { it.copy(expiresAtMillis = expiresAt) }

                when (result.status.lowercase()) {
                    "consumed" -> {
                        cancelPolling()
                        // The payload is present exactly once (when it first flips to consumed).
                        // A null payload here means we lost the single-use race / a stale re-poll —
                        // treat it as expired so the user can retry rather than hang.
                        val account = pairingPayloadToXtreamAccount(result.payload)
                        if (account != null) {
                            savePairedAccount(account)
                        } else {
                            _uiState.update { it.copy(status = IptvPairingStatus.EXPIRED) }
                        }
                    }
                    "expired" -> {
                        cancelPolling()
                        _uiState.update { it.copy(status = IptvPairingStatus.EXPIRED) }
                    }
                    // "pending" -> keep waiting.
                    else -> Unit
                }
            },
            onFailure = { e ->
                // A wrong-secret / network error: surface it but keep the session (the loop retries
                // on the next tick unless it's clearly fatal — here we just log and keep waiting).
                Log.w("IptvPairingViewModel", "poll failed, will retry", e)
            }
        )
    }

    private suspend fun savePairedAccount(account: XtreamAccount) {
        _uiState.update { it.copy(status = IptvPairingStatus.SAVING) }
        runCatching {
            accountStore.upsert(account)
            syncService.triggerRemoteSync()
        }.onSuccess {
            _uiState.update {
                it.copy(status = IptvPairingStatus.SUCCESS, savedAccountName = account.name)
            }
        }.onFailure { e ->
            _uiState.update {
                it.copy(status = IptvPairingStatus.ERROR, errorMessage = friendlyError(e))
            }
        }
    }

    private fun cancelPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun friendlyError(e: Throwable): String {
        val message = e.message.orEmpty().lowercase()
        return when {
            message.contains("unable to resolve host") || message.contains("no address associated") ->
                "No internet connection. Check your network and try again."
            message.contains("timeout") || message.contains("timed out") ->
                "The connection timed out. Try again."
            message.contains("in use") -> "That code is already in use. Try again to get a new one."
            else -> "Something went wrong. Try again."
        }
    }

    override fun onCleared() {
        cancelPolling()
        super.onCleared()
    }
}
