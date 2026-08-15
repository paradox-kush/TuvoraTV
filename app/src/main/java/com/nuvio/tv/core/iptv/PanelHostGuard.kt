package com.nuvio.tv.core.iptv


/**
 * How a guarded panel request ended. The transport at the call site classifies its own error into
 * one of these; the guard itself never inspects exceptions, so the policy stays platform-free.
 */
enum class PanelRequestOutcome {
    /**
     * The host never answered: timeout, DNS failure, connection refused, host/network unreachable.
     * The only outcome that counts toward opening the breaker.
     */
    CONNECTION_FAILURE,

    /**
     * The connection was reset mid-transfer (ECONNRESET). Hosts that reset are very much alive, so
     * this NEVER counts as a failure — but it proves nothing either, so it does not clear the
     * record. Inconclusive: its only effect is to release a held half-open trial slot. Report a
     * cancelled request as this too, so an abandoned trial cannot wedge the breaker.
     */
    CONNECTION_RESET,

    /**
     * ANY HTTP status came back — a 404 or a 502 as much as a 200. The host answered, which is all
     * the breaker measures: the record is cleared unconditionally.
     */
    HTTP_RESPONSE,

    /** The request fully succeeded. Clears the record, exactly like [HTTP_RESPONSE]. */
    SUCCESS,
}

/**
 * The guard's answer to [PanelHostGuard.admit]. [FastFail] is a distinct type precisely so callers
 * can tell "the breaker refused to try" apart from a real network error.
 */
sealed interface PanelAdmission {
    /** The normalized origin key the decision was made for (never contains credentials). */
    val origin: String

    /**
     * Proceed with the request, then hand this token back to [PanelHostGuard.report] with the
     * outcome — including on failure and cancellation (report [PanelRequestOutcome.CONNECTION_RESET]
     * for a cancel). An unreported trial token holds the half-open slot until it expires.
     */
    class Allowed internal constructor(
        override val origin: String,
        internal val epoch: Int,
        internal val admittedAtMillis: Long,
        internal val trialSlotId: Long,
        internal val discovery: Boolean,
    ) : PanelAdmission {
        /** True when this admission is the single half-open trial for an open breaker. */
        val isTrial: Boolean get() = trialSlotId != 0L
    }

    /**
     * Do NOT contact the host: the breaker is open for this origin. [retryAtMillis] is the earliest
     * clock instant a new admission could be allowed (assuming nothing settles the breaker sooner).
     */
    class FastFail internal constructor(
        override val origin: String,
        val retryAtMillis: Long,
    ) : PanelAdmission {
        /** The throwable form of this refusal, for call sites whose failure path is an exception. */
        fun toException(): PanelHostFastFailException = PanelHostFastFailException(origin, retryAtMillis)
    }
}

/**
 * The fast-fail as an error, for wiring into call sites that propagate failures as exceptions.
 * A DISTINCT type: catching code can (and must) tell it apart from a real network error.
 *
 * The message wording is load-bearing: callers that classify transport failures from message text
 * must read this as a connection-level refusal, so it must never contain `HTTP Error <code>`,
 * `timeout`/`timed out`, or auth phrases — and it names the full origin, scheme included, so a
 * user who imported the same panel over both HTTP and HTTPS can tell which one was skipped.
 * The origin never carries credentials (see [PanelHostGuard.originOf]).
 */
class PanelHostFastFailException(
    val origin: String,
    val retryAtMillis: Long,
) : Exception("Skipped contacting $origin because it has not been answering; it will be retried shortly")

/**
 * Per-origin circuit breaker for IPTV panel API requests — iptvnator's proven
 * `HostConnectivityGuard` contract (see `research/reference-players/iptvnator/CLAUDE.md` and its
 * `docs/architecture/host-connectivity-guard.md`) as a pure, transport-free policy.
 *
 * THE PROBLEM: every request to an unreachable panel costs its full timeout, and browsing a dead
 * panel's catalog issues dozens of those back to back — 30-second spinners all the way down. Once
 * a host has refused to answer twice in a row there is nothing left to learn from waiting again.
 *
 * Being wrong here means refusing to talk to a panel that works, so every rule errs toward
 * contacting the host:
 *
 *  - KEY = URL origin (scheme + host + port), NOT the bare host. `http://panel` and
 *    `https://panel` are two genuinely different endpoints — a panel whose TLS listener is dead
 *    while plain HTTP works is a routine IPTV setup, and sharing one record would let the dead
 *    listener fast-fail the working one without ever contacting it.
 *  - Only CONNECTION-LEVEL failures count ([PanelRequestOutcome.CONNECTION_FAILURE]). ECONNRESET
 *    never counts, and an HTTP response NEVER counts — any status, 4xx/5xx included, proves the
 *    host is alive and CLEARS the record.
 *  - [FAILURE_THRESHOLD] (2) consecutive counted failures open the breaker: admissions fast-fail
 *    for [OPEN_DURATION_MILLIS] (30 s).
 *  - After the window, exactly ONE half-open trial is admitted. Its success (or any HTTP response)
 *    closes the breaker; its connection failure re-opens it for another full window. A trial that
 *    never reports back expires after [TRIAL_EXPIRY_MILLIS] so a leaked token cannot wedge the
 *    breaker, and the slot has an identity so the abandoned trial's late report cannot free or
 *    settle its replacement's slot.
 *  - Siblings are not a streak: catalog fan-out fails several requests on one network hiccup, so a
 *    failure only counts if its request was admitted at or after the previous counted failure.
 *  - [reset] backs every user-driven Retry affordance — a user pressing Retry must never be met
 *    with a fast-fail. It also bumps the origin's epoch, so the 30-second stragglers the user was
 *    waiting behind settle into the OLD epoch and are discarded instead of re-opening the breaker
 *    underneath the very retry that cleared it.
 *  - Discovery probes (`admit(url, discovery = true)`) bypass the breaker and never count their
 *    failures — endpoint discovery expects most candidates to fail — but their successes still
 *    clear the record, so a probe that reaches the host un-trips it.
 *
 * The clock is injected and the policy never reads system time itself; give it a monotonic
 * elapsed-time source in production so wall-clock jumps cannot reopen or hold the breaker.
 *
 * Thread-safe (requests fan out across dispatcher threads); pure policy, NOT yet wired into
 * XtreamClient/StalkerClient — wiring happens at integration, where each call site classifies its
 * own errors into [PanelRequestOutcome].
 *
 * JVM port of the byte-identical NuvioMobile/NuvioDesktop commonMain twins (com.nuvio.app.features.iptv).
 */
class PanelHostGuard(private val nowMillis: () -> Long) {

    private class OriginRecord {
        var consecutiveFailures: Int = 0
        var lastCountedFailureAtMillis: Long = 0L
        var openUntilMillis: Long = 0L
        var trialSlotId: Long = 0L
        var trialAdmittedAtMillis: Long = 0L
        var epoch: Int = 0

        /** Forget everything except [epoch], which must keep invalidating in-flight reports. */
        fun clear() {
            consecutiveFailures = 0
            lastCountedFailureAtMillis = 0L
            openUntilMillis = 0L
            trialSlotId = 0L
            trialAdmittedAtMillis = 0L
        }
    }

    private val lock = Any()
    private val records = HashMap<String, OriginRecord>()
    private var nextTrialSlotId: Long = 0L

    /**
     * Ask whether a request to [url] may be sent. Returns [PanelAdmission.Allowed] (send it, then
     * [report] the outcome with the returned token) or [PanelAdmission.FastFail] (the breaker is
     * open — do not contact the host).
     *
     * [discovery] marks an endpoint-discovery probe: admitted even while the breaker is open, and
     * its failures are never counted — but its successes still clear the record.
     */
    fun admit(url: String, discovery: Boolean = false): PanelAdmission {
        val origin = originOf(url)
        val now = nowMillis()
        return synchronized(lock) {
            val record = records[origin]
            val epoch = record?.epoch ?: 0
            if (discovery) {
                return@synchronized PanelAdmission.Allowed(origin, epoch, now, trialSlotId = 0L, discovery = true)
            }
            if (record == null || record.openUntilMillis == 0L) {
                return@synchronized PanelAdmission.Allowed(origin, epoch, now, trialSlotId = 0L, discovery = false)
            }
            if (now < record.openUntilMillis) {
                return@synchronized PanelAdmission.FastFail(origin, record.openUntilMillis)
            }
            // The open window has passed: half-open. Exactly one trial may be out at a time —
            // unless the previous trial leaked (never reported) and its slot has expired.
            val trialHeld = record.trialSlotId != 0L &&
                now - record.trialAdmittedAtMillis < TRIAL_EXPIRY_MILLIS
            if (trialHeld) {
                return@synchronized PanelAdmission.FastFail(
                    origin,
                    record.trialAdmittedAtMillis + TRIAL_EXPIRY_MILLIS,
                )
            }
            val slot = ++nextTrialSlotId
            record.trialSlotId = slot
            record.trialAdmittedAtMillis = now
            PanelAdmission.Allowed(origin, epoch, now, trialSlotId = slot, discovery = false)
        }
    }

    /** Report how the admitted request ended. Safe to call from any thread; never throws. */
    fun report(admission: PanelAdmission.Allowed, outcome: PanelRequestOutcome) {
        val origin = admission.origin
        val now = nowMillis()
        synchronized(lock) {
            when (outcome) {
                PanelRequestOutcome.SUCCESS, PanelRequestOutcome.HTTP_RESPONSE -> {
                    // The host answered. Clears unconditionally — stale-epoch and discovery
                    // reports included, because clearing errs toward contacting the host.
                    records[origin]?.clear()
                }

                PanelRequestOutcome.CONNECTION_RESET -> {
                    val record = records[origin] ?: return@synchronized
                    if (admission.epoch != record.epoch) return@synchronized
                    // Inconclusive: only the half-open slot is released, and only by its owner.
                    if (admission.trialSlotId != 0L && admission.trialSlotId == record.trialSlotId) {
                        record.trialSlotId = 0L
                        record.trialAdmittedAtMillis = 0L
                    }
                }

                PanelRequestOutcome.CONNECTION_FAILURE -> {
                    // Discovery expects most candidates to fail; that is never evidence.
                    if (admission.discovery) return@synchronized
                    val record = records.getOrPut(origin) { OriginRecord() }
                    // A report into an epoch a reset has since retired is a straggler the user
                    // already dismissed: discard it, or it would re-open the breaker underneath
                    // the very Retry that cleared it.
                    if (admission.epoch != record.epoch) return@synchronized
                    if (admission.trialSlotId != 0L && admission.trialSlotId == record.trialSlotId) {
                        // The half-open trial failed: straight back to open for a full window.
                        record.trialSlotId = 0L
                        record.trialAdmittedAtMillis = 0L
                        record.consecutiveFailures += 1
                        record.lastCountedFailureAtMillis = now
                        record.openUntilMillis = now + OPEN_DURATION_MILLIS
                        return@synchronized
                    }
                    // Siblings are not a streak: a request that started before the previous
                    // failure was recorded is the same piece of evidence, not a second one.
                    // (An expired trial's late failure lands here too — ordinary evidence.)
                    if (admission.admittedAtMillis < record.lastCountedFailureAtMillis) {
                        return@synchronized
                    }
                    record.consecutiveFailures += 1
                    record.lastCountedFailureAtMillis = now
                    if (record.consecutiveFailures >= FAILURE_THRESHOLD) {
                        val until = now + OPEN_DURATION_MILLIS
                        if (until > record.openUntilMillis) record.openUntilMillis = until
                    }
                }
            }
        }
    }

    /**
     * User-driven reset for [url]'s origin: every Retry/refresh affordance calls this BEFORE its
     * first request, so a user pressing Retry is never met with a fast-fail. Clears the record and
     * retires its epoch — failures already in flight settle into the old epoch and are discarded.
     * Automatic paths must NOT call this: only a user action means "contact this host now".
     */
    fun reset(url: String) {
        val origin = originOf(url)
        synchronized(lock) {
            val record = records.getOrPut(origin) { OriginRecord() }
            record.clear()
            record.epoch += 1
        }
    }

    companion object {
        /** Counted connection failures in a row that open the breaker. */
        const val FAILURE_THRESHOLD: Int = 2

        /** How long an open breaker fast-fails before allowing the half-open trial. */
        const val OPEN_DURATION_MILLIS: Long = 30_000L

        /** How long an unreported half-open trial holds its slot before it is reclaimed. */
        const val TRIAL_EXPIRY_MILLIS: Long = 45_000L

        /**
         * The breaker key for [url]: `scheme://host[:port]`, lowercased, default ports (80/443)
         * elided, and any `user:pass@` userinfo stripped — no credential ever reaches the key or
         * a log line built from it. Unparseable input degrades to a deterministic best-effort key
         * rather than throwing, so a malformed URL can never crash the request path.
         */
        fun originOf(url: String): String {
            val trimmed = url.trim()
            val schemeEnd = trimmed.indexOf("://")
            val scheme: String
            val afterScheme: String
            if (schemeEnd >= 0) {
                scheme = trimmed.substring(0, schemeEnd).lowercase()
                afterScheme = trimmed.substring(schemeEnd + 3)
            } else {
                scheme = ""
                afterScheme = trimmed
            }
            var authority = afterScheme
            for (i in afterScheme.indices) {
                val c = afterScheme[i]
                if (c == '/' || c == '?' || c == '#') {
                    authority = afterScheme.substring(0, i)
                    break
                }
            }
            authority = authority.substringAfterLast('@')
            val host: String
            val port: String
            if (authority.startsWith("[")) {
                // IPv6 literal: the port separator is the colon AFTER the closing bracket.
                val end = authority.indexOf(']')
                if (end >= 0) {
                    host = authority.substring(0, end + 1)
                    port = authority.substring(end + 1).removePrefix(":")
                } else {
                    host = authority
                    port = ""
                }
            } else {
                val colon = authority.lastIndexOf(':')
                if (colon >= 0) {
                    host = authority.substring(0, colon)
                    port = authority.substring(colon + 1)
                } else {
                    host = authority
                    port = ""
                }
            }
            val defaultPort = when (scheme) {
                "http" -> "80"
                "https" -> "443"
                else -> null
            }
            val portSuffix = if (port.isEmpty() || port == defaultPort) "" else ":$port"
            val loweredHost = host.lowercase()
            return if (scheme.isEmpty()) loweredHost + portSuffix else "$scheme://$loweredHost$portSuffix"
        }
    }
}
