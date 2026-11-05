package com.nuvio.tv.core.livepause

import com.nuvio.tv.core.contracts.LivePauseEngineFacts
import com.nuvio.tv.playback.core.ApplicationDnsKey
import com.nuvio.tv.playback.core.PlaybackNetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

// The ports of [LivePauseController]: the only way it touches the player, the capture's files and sockets,
// and the provider. TV twin of NuvioMobile's commonMain livetv/pause ports.

/** A freshly minted playable link, with the headers the provider expects (User-Agent included). */
data class LiveLink(
    val url: String,
    val headers: Map<String, String>,
    /** The playlist's pinned DNS, when it has one (the same selector the player's request carries). */
    val dnsKey: ApplicationDnsKey? = null,
    /** The resolver's network policy (proxy, timeouts), so the capture reaches the provider as the player does. */
    val network: PlaybackNetworkRequest = PlaybackNetworkRequest(),
    /** False when the resolver rejects redirects (RedirectPolicy.REJECT). */
    val followRedirects: Boolean = true,
)

/** Mints a link through the app's live-resolve path. Null = the provider refused. */
fun interface LiveLinkMinter {
    suspend fun mint(): LiveLink?
}

data class LiveCaptureSpec(
    val instanceId: String,
    val minter: LiveLinkMinter,
    val singleUseLink: Boolean,
    val keepMs: Long,
    val bitrateBps: Long?,
    val maxCaptureBitrateBps: Long,
    /** Shared with the controller: every mint (open, reconnect, Retry) draws from it. */
    val linkBudget: LiveLinkBudget = LiveLinkBudget(),
)

/**
 * The bounded count of links one pause episode may mint, shared by the capture (first open and reconnects)
 * and the controller (Retry), so a single-use provider is never asked for more than [max] links without a
 * piece landing in between. [refill] is the "progress" signal.
 */
class LiveLinkBudget(private val max: Int = DEFAULT_MAX) {
    private val used = java.util.concurrent.atomic.AtomicInteger(0)

    /** Takes one mint. False = the budget is spent: stop asking the provider. */
    fun tryAcquire(): Boolean {
        while (true) {
            val now = used.get()
            if (now >= max) return false
            if (used.compareAndSet(now, now + 1)) return true
        }
    }

    fun hasRemaining(): Boolean = used.get() < max

    /** A piece landed: the count starts over. */
    fun refill() = used.set(0)

    companion object {
        /** First open + the capture's 3 capped reconnects (LiveCaptureReconnect). */
        const val DEFAULT_MAX = 4
    }
}

sealed interface LiveCaptureEvent {
    /** The first playable piece is on disk; [playlistUri] is a `file://` URL of the local playlist. */
    data class Ready(val playlistUri: String) : LiveCaptureEvent

    data class Window(val totalMs: Long, val trimmedMs: Long, val bufferFull: Boolean) : LiveCaptureEvent
    data class Failed(val reason: LivePauseFailure) : LiveCaptureEvent
}

/** One capture of one stream, owned by one controller. */
interface LiveCapture {
    val events: Flow<LiveCaptureEvent>

    /** Opens the stream and starts writing. */
    fun start()

    /** Cancels the capture and deletes its files. Idempotent, never blocks the caller. */
    fun dispose()
}

fun interface LiveCaptureFactory {
    fun create(spec: LiveCaptureSpec, scope: CoroutineScope): LiveCapture
}

/** The device facts the controller decides from. Read when a pause starts, never cached. */
class LivePauseEnvironment(
    val facts: () -> LivePauseEngineFacts,
    val lowMemoryTier: () -> Boolean,
    val freeBytes: () -> Long,
    val engineMemoryBudgetBytes: () -> Long,
    val normalEnginePlan: () -> LivePausePolicy.EngineCachePlan,
    val singleUseLink: () -> Boolean,
    val keepMs: Long = LivePausePolicy.DEFAULT_KEEP_MS,
)
