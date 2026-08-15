package com.nuvio.tv.core.iptv

/**
 * Walks the catch-up URL dialects for one replay until one plays.
 *
 * Panels do not agree on the timeshift URL shape and none advertise which they speak, so the only
 * honest detector is the real playback attempt — never an out-of-band probe, which on a
 * max_connections=1 account kicks the live stream (the same reason the .ts→HLS fix refused to
 * probe). The caller performs each attempt and reports what happened; this object only decides
 * what to try next, in which order, and what to remember.
 *
 * Pure policy: no network, no persistence — [WinnerMemory] is injected and a later lane wires it
 * to disk. Drive it from one dispatcher: the state is deliberately unsynchronized.
 */
class CatchUpDialectWalk(private val memory: WinnerMemory) {

    /** What a dialect asks the panel to emit. */
    enum class Container { TS, M3U8, PANEL_DEFAULT }

    /**
     * Dialect × container — the stable identity a winner is remembered by. The five URL forms come
     * from [XtreamCatchUp.candidateUrls]; the extension-bearing forms exist in both containers,
     * the extension-less php forms serve whatever the panel defaults to.
     */
    enum class Dialect(val container: Container) {
        PATH_TS(Container.TS),
        PATH_SWAPPED_TS(Container.TS),
        PHP_STREAMING_EXT_TS(Container.TS),
        PHP_STREAMING(Container.PANEL_DEFAULT),
        PHP_ROOT(Container.PANEL_DEFAULT),
        PATH_M3U8(Container.M3U8),
        PATH_SWAPPED_M3U8(Container.M3U8),
        PHP_STREAMING_EXT_M3U8(Container.M3U8),
    }

    /**
     * How an attempt failed. TRANSPORT (404/timeout/connection refused) means the URL shape may be
     * wrong — walk on. DECODE means the URL reached a stream whose content is broken — a different
     * shape replays the same broken recording, so the walk stops.
     */
    enum class FailureKind { TRANSPORT, DECODE }

    /** A winner proven under one allowed-formats signature; a different signature voids the proof. */
    data class StoredWinner(val formatsSignature: String, val dialect: Dialect)

    /** Injected storage, keyed by account. Policy decides WHEN to read and write; storage just holds. */
    interface WinnerMemory {
        fun recall(accountId: String): StoredWinner?
        fun remember(accountId: String, winner: StoredWinner)
    }

    /** One replay ask. Equality is the single-flight key: the same ask joins the walk in flight. */
    data class Request(
        val accountId: String,
        val baseUrl: String,
        val username: String,
        val password: String,
        val streamId: Int,
        val startMs: Long,
        val endMs: Long,
        /** The panel's `allowed_output_formats` when known; null/empty = prune nothing. */
        val allowedOutputFormats: List<String>? = null,
        /** The per-playlist scrub-bar preference: reorder m3u8-first, TS retained as fallback. */
        val preferM3u8: Boolean = false,
        val serverTimeZone: String? = null,
        val serverOffsetMs: Long? = null,
    )

    /** One URL to try. The token names exactly this attempt — a result quoting an old one is stale. */
    data class Attempt(val token: Long, val dialect: Dialect, val url: String)

    sealed interface Step {
        /** Try this. */
        data class Next(val attempt: Attempt) : Step

        /** Terminal: nothing (left) worth trying — tell the viewer the programme is unavailable. */
        object Unavailable : Step

        /** The success is recorded; the walk is over. */
        object Done : Step

        /** The report belonged to a superseded or finished walk — ignore it entirely. */
        object Stale : Step
    }

    private class Session(
        val request: Request,
        val candidates: List<Pair<Dialect, String>>,
    ) {
        var index = 0
        var token = 0L
        fun attempt(): Attempt {
            val (dialect, url) = candidates[index]
            return Attempt(token, dialect, url)
        }
    }

    /** At most one walk per account — the single-flight guard the references both carry. */
    private val sessions = HashMap<String, Session>()
    private var tokenSeq = 0L

    /**
     * Starts (or joins) the walk for [request] and answers the first attempt.
     *
     * The identical request while a walk is in flight joins it — concurrent resolutions must not
     * stampede the panel. A DIFFERENT request for the same account (the viewer zapped) supersedes
     * the old walk; its token dies with it, so its late results land as [Step.Stale].
     */
    fun begin(request: Request): Step {
        val active = sessions[request.accountId]
        if (active != null && active.request == request) return Step.Next(active.attempt())

        val urls = urlsByDialect(request) ?: return Step.Unavailable
        val order = winnerFirst(request, prune(walkOrder(request.preferM3u8), request.allowedOutputFormats))
        if (order.isEmpty()) return Step.Unavailable

        val session = Session(request, order.map { it to urls.getValue(it) })
        session.token = ++tokenSeq
        sessions[request.accountId] = session
        return Step.Next(session.attempt())
    }

    /** The current attempt played: remember the proof, end the walk. */
    fun onSuccess(token: Long): Step {
        val session = sessionFor(token) ?: return Step.Stale
        memory.remember(
            session.request.accountId,
            StoredWinner(
                formatsSignature(session.request.allowedOutputFormats),
                session.attempt().dialect,
            ),
        )
        sessions.remove(session.request.accountId)
        return Step.Done
    }

    /**
     * The current attempt failed. TRANSPORT advances the ladder; DECODE ends the walk — the URL
     * reached a stream, so no other shape will do better. Exhaustion ends it too, with nothing
     * pinned: a dead stream_id (a renumbered catalog) or a panel briefly down must not poison the
     * winner memory (iptvnator persists its fallback here, arguably their bug).
     */
    fun onFailure(token: Long, kind: FailureKind): Step {
        val session = sessionFor(token) ?: return Step.Stale
        return when (kind) {
            FailureKind.DECODE -> {
                sessions.remove(session.request.accountId)
                Step.Unavailable
            }
            FailureKind.TRANSPORT -> {
                session.index++
                if (session.index >= session.candidates.size) {
                    sessions.remove(session.request.accountId)
                    Step.Unavailable
                } else {
                    session.token = ++tokenSeq
                    Step.Next(session.attempt())
                }
            }
        }
    }

    /** A token names one attempt of the one active walk per account; anything else is dead. */
    private fun sessionFor(token: Long): Session? =
        sessions.values.firstOrNull { it.token == token }

    /**
     * Every dialect's URL, positionally off [XtreamCatchUp.candidateUrls] — whose order its own
     * test pins byte-exactly. The size guard turns any future drift into a loud Unavailable
     * instead of silently mismapped URLs; it also absorbs the blank-credentials empty list.
     */
    private fun urlsByDialect(request: Request): Map<Dialect, String>? {
        val ts = candidateUrls(request, "ts")
        val m3u8 = candidateUrls(request, "m3u8")
        if (ts.size != 5 || m3u8.size != 5) return null
        return mapOf(
            Dialect.PATH_TS to ts[0],
            Dialect.PATH_SWAPPED_TS to ts[1],
            Dialect.PHP_STREAMING_EXT_TS to ts[2],
            Dialect.PHP_STREAMING to ts[3],
            Dialect.PHP_ROOT to ts[4],
            Dialect.PATH_M3U8 to m3u8[0],
            Dialect.PATH_SWAPPED_M3U8 to m3u8[1],
            Dialect.PHP_STREAMING_EXT_M3U8 to m3u8[2],
        )
    }

    private fun candidateUrls(request: Request, container: String): List<String> =
        XtreamCatchUp.candidateUrls(
            baseUrl = request.baseUrl,
            username = request.username,
            password = request.password,
            streamId = request.streamId,
            startMs = request.startMs,
            endMs = request.endMs,
            containerExtension = container,
            serverTimeZone = request.serverTimeZone,
            serverOffsetMs = request.serverOffsetMs,
        )

    /**
     * The preferred container's dialects first — the shipped five-URL walk when TS leads — then
     * the panel-default php forms, then the other container as fallback.
     */
    private fun walkOrder(preferM3u8: Boolean): List<Dialect> = if (preferM3u8) listOf(
        Dialect.PATH_M3U8, Dialect.PATH_SWAPPED_M3U8, Dialect.PHP_STREAMING_EXT_M3U8,
        Dialect.PHP_STREAMING, Dialect.PHP_ROOT,
        Dialect.PATH_TS, Dialect.PATH_SWAPPED_TS, Dialect.PHP_STREAMING_EXT_TS,
    ) else listOf(
        Dialect.PATH_TS, Dialect.PATH_SWAPPED_TS, Dialect.PHP_STREAMING_EXT_TS,
        Dialect.PHP_STREAMING, Dialect.PHP_ROOT,
        Dialect.PATH_M3U8, Dialect.PATH_SWAPPED_M3U8, Dialect.PHP_STREAMING_EXT_M3U8,
    )

    /**
     * Drops dialects whose container the panel says it cannot emit. Panel-default forms are never
     * pruned (they ask nothing). A list naming NEITHER container is nonsense for catch-up
     * purposes — junk panel data must not gut the ladder, so it prunes nothing.
     */
    private fun prune(order: List<Dialect>, allowedOutputFormats: List<String>?): List<Dialect> {
        val formats = normalizedFormats(allowedOutputFormats)
        if (formats.isEmpty()) return order
        if ("ts" !in formats && "m3u8" !in formats) return order
        return order.filter { dialect ->
            when (dialect.container) {
                Container.PANEL_DEFAULT -> true
                Container.TS -> "ts" in formats
                Container.M3U8 -> "m3u8" in formats
            }
        }
    }

    /**
     * A winner proven under the SAME formats signature leads the walk; the rest keeps its order as
     * the fallback ladder. A different signature voids the proof — the panel changed what it can
     * emit, so the walk starts from the top (the winner is forgotten, not misapplied).
     */
    private fun winnerFirst(request: Request, pruned: List<Dialect>): List<Dialect> {
        val stored = memory.recall(request.accountId) ?: return pruned
        if (stored.formatsSignature != formatsSignature(request.allowedOutputFormats)) return pruned
        if (stored.dialect !in pruned) return pruned
        return listOf(stored.dialect) + pruned.filterNot { it == stored.dialect }
    }

    companion object {
        /**
         * The allowed-formats signature a winner is proven under: normalized (trim/lowercase),
         * deduped, sorted, comma-joined — or "unknown" when the panel never said. Sorted so two
         * orderings of the same list cannot void a proof (iptvnator broke this three times).
         */
        fun formatsSignature(allowedOutputFormats: List<String>?): String {
            val formats = normalizedFormats(allowedOutputFormats)
            return if (formats.isEmpty()) "unknown" else formats.sorted().joinToString(",")
        }

        private fun normalizedFormats(allowedOutputFormats: List<String>?): Set<String> =
            allowedOutputFormats.orEmpty()
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
    }
}
