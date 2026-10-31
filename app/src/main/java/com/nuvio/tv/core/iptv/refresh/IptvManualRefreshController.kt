package com.nuvio.tv.core.iptv.refresh

import com.nuvio.tv.core.iptv.BoundedLoad
import com.nuvio.tv.core.iptv.IptvImportProgress
import com.nuvio.tv.core.iptv.IptvManualRefreshPolicy
import com.nuvio.tv.core.iptv.LoadOutcome
import com.nuvio.tv.core.iptv.LoadStatus
import com.nuvio.tv.core.iptv.LoadSurface
import com.nuvio.tv.core.iptv.ManualRefreshResult
import com.nuvio.tv.core.iptv.XtreamAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the "Refresh now" card shows. [result] is the last finished outcome (null until one has finished). */
data class ManualRefreshUiState(
    /** Entered only through [BoundedLoad]: Loaded or Failed always ends, and a failure is never "done". */
    val load: LoadStatus = LoadStatus.Idle,
    val result: ManualRefreshResult? = null,
    /** When this playlist's catalog was last rebuilt (null = unknown). */
    val lastRefreshAtMs: Long? = null,
    /** Wall-clock time this state was published; "Updated N ago" is measured against it. */
    val publishedAtMs: Long = 0L,
) {
    val refreshing: Boolean get() = load is LoadStatus.Loading
    val age: IptvManualRefreshPolicy.Age? get() = IptvManualRefreshPolicy.age(lastRefreshAtMs, publishedAtMs)
}

/** State holder for "Refresh now" on one playlist (the card only renders it). One refresh at a time; cooldown is in the engine. */
class IptvManualRefreshController(
    private val scope: CoroutineScope,
    private val refresh: suspend (XtreamAccount) -> ManualRefreshResult,
    private val lastRefreshAt: suspend (XtreamAccount) -> Long?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val mutableState = MutableStateFlow(ManualRefreshUiState())
    val state: StateFlow<ManualRefreshUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    /** Reads the last-refreshed time for the "Updated N ago" line (a failed read leaves it unknown). */
    fun load(account: XtreamAccount) {
        scope.launch {
            val at = readLastRefresh(account)
            val current = mutableState.value
            // A refresh that started (or finished) while this read was in flight already carries the newer time.
            if (current.refreshing || current.result != null) return@launch
            mutableState.value = current.copy(lastRefreshAtMs = at, publishedAtMs = nowMs())
        }
    }

    private suspend fun readLastRefresh(account: XtreamAccount): Long? = try {
        lastRefreshAt(account)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }

    fun start(account: XtreamAccount) {
        if (mutableState.value.refreshing) return
        val known = mutableState.value.lastRefreshAtMs
        mutableState.value = ManualRefreshUiState(load = BoundedLoad.begin(LoadSurface.PLAYLIST_REFRESH), lastRefreshAtMs = known, publishedAtMs = nowMs())
        job = scope.launch {
            val outcome = BoundedLoad.run(
                LoadSurface.PLAYLIST_REFRESH,
                progress = IptvImportProgress.of(account.id),
                onProgress = { mutableState.value = mutableState.value.copy(load = it) },
                // The rebuild is real work the viewer asked for: past the stall deadline the screen stops waiting, it keeps going.
                cancelOnTimeout = false,
                report = mapOf("source" to account.sourceType),
            ) { refresh(account) }
            val result = (outcome as? LoadOutcome.Loaded)?.value ?: (outcome as? LoadOutcome.Empty)?.value
            val at = readLastRefresh(account) ?: known
            mutableState.value = when {
                outcome is LoadOutcome.Failed -> ManualRefreshUiState(outcome.status, ManualRefreshResult.FAILED, at, nowMs())
                // Only REFRESHED is a success; PARTIAL / FAILED are failures the viewer can retry, never "done".
                result == ManualRefreshResult.PARTIAL || result == ManualRefreshResult.FAILED ->
                    ManualRefreshUiState(LoadStatus.Failed(timedOut = false), result, at, nowMs())
                else -> ManualRefreshUiState(LoadStatus.Loaded, result, at, nowMs())
            }
        }
    }

    fun cancel() { job?.cancel() }
}
