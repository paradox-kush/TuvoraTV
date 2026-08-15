package com.nuvio.tv.core.iptv

/**
 * The guide's per-channel EPG source ladder: manual mapping → the provider's own rows if they
 * pass a sanity gate → the mirrored canonical EPG → nothing.
 *
 * Replaces the guide's old provider-first `.ifEmpty { mirror }` fallback, whose one measured
 * failure mode was that present-but-garbage beat absent: wa12's skewed short-EPG rows (every
 * epoch one zone-offset in the future, nothing bracketing now) suppressed the mirror entirely
 * and rendered a fully empty visible guide, while on a real Stalker portal the mirror mapped
 * 12% of channels (1,410 of 11,283) with zero visibility into any of it. The ladder makes "the
 * panel said something useless" fall toward the mirror, and the settings coverage line makes
 * the per-source split visible.
 *
 * Pure on purpose (no clock, no I/O of its own): every rung is a caller-supplied fetcher invoked
 * lazily, so a channel remembered as mirror-fed never re-asks the panel, and the policy is
 * testable with plain lambdas. [XtreamEpochSkew]'s parse-boundary correction has ALREADY run by
 * the time rows reach the gate — the gate judges corrected epochs, so a liar panel the detector
 * repaired passes on its own rows, and a skew the detector could not prove falls to the mirror
 * instead of suppressing it.
 */
object EpgSourceLadder {

    /** Which rung answered for a channel. */
    enum class Source { MANUAL, PROVIDER, MIRROR, NONE }

    data class Resolution(val source: Source, val programmes: List<XtreamProgram>)

    /**
     * The manual-mapping seam — the top rung of the ladder, above every automatic source.
     *
     * Today no implementation exists and every wiring passes null: the queued manual-EPG-mapping
     * design (P1.4) plugs in here by handing the guide a resolver that answers the user's explicit
     * channel→EPG assignments. The contract: return the mapped programmes for this channel, or
     * null when the user has not mapped it — a null (or empty) answer falls through to the
     * automatic rungs, a non-empty answer wins unconditionally (no sanity gate: an explicit user
     * mapping is never second-guessed).
     */
    fun interface ManualResolver {
        suspend fun programmesFor(accountId: String, streamId: Int, nowMs: Long): List<XtreamProgram>?
    }

    /**
     * Boundary tolerance for the sanity gate. A row counts as bracketing now if any instant
     * within this slack of now falls inside its span — so a programme that just ended, or one
     * about to start, doesn't flap the channel between sources at every schedule boundary.
     *
     * Deliberately small: the lies the gate exists to catch are zone-offset skews, and the
     * smallest real zone step is 15 minutes — a slack anywhere near the visible guide window
     * would readmit exactly the wa12 shape (first row +1.43 h out) the gate is for.
     */
    const val GATE_SLACK_MS = 5 * 60_000L

    /**
     * The sanity gate: the provider's response is trusted only when at least one row brackets
     * now (± [GATE_SLACK_MS]). A live guide's short EPG always claims to start at the airing
     * programme, so a response where nothing spans now is either empty (fails trivially —
     * preserving the old `.ifEmpty` fallback exactly) or skewed in a way the parse-boundary
     * correction could not prove — and both belong to the mirror, not on screen.
     */
    fun providerPassesGate(rows: List<XtreamProgram>, nowMs: Long): Boolean =
        rows.any { it.startMs <= nowMs + GATE_SLACK_MS && nowMs - GATE_SLACK_MS < it.endMs }

    /**
     * Resolves one channel's guide programmes. Rungs are invoked lazily and in ladder order;
     * [remembered] short-circuits the panel ask for a channel that already fell to the mirror
     * (the memory is a hint, never a cage — a mirror that stops answering falls back through
     * the full ladder, and a remembered provider is still gated every time).
     */
    suspend fun resolve(
        nowMs: Long,
        remembered: Source? = null,
        manual: (suspend () -> List<XtreamProgram>?)? = null,
        provider: suspend () -> List<XtreamProgram>,
        mirror: suspend () -> List<XtreamProgram>,
    ): Resolution {
        // Rung 1 — manual mapping. An explicit user assignment outranks everything, including
        // the memory: the user may map a channel precisely because the remembered source is bad.
        manual?.invoke()?.takeIf { it.isNotEmpty() }?.let { return Resolution(Source.MANUAL, it) }

        // A channel that already fell to the mirror goes straight back to it — the whole point
        // of the memory is that garbage panels are not re-asked on every focus.
        if (remembered == Source.MIRROR) {
            val fromMirror = mirror()
            if (fromMirror.isNotEmpty()) return Resolution(Source.MIRROR, fromMirror)
            // The mirror dried up (mapping purged, ingest gap): fall through — but don't ask it
            // twice below, it just answered.
            val fromProvider = provider()
            if (providerPassesGate(fromProvider, nowMs)) return Resolution(Source.PROVIDER, fromProvider)
            return Resolution(Source.NONE, emptyList())
        }

        // Rung 2 — the provider's own rows, behind the sanity gate.
        val fromProvider = provider()
        if (providerPassesGate(fromProvider, nowMs)) return Resolution(Source.PROVIDER, fromProvider)

        // Rung 3 — the mirror window. Empty-or-garbage provider responses both land here.
        val fromMirror = mirror()
        if (fromMirror.isNotEmpty()) return Resolution(Source.MIRROR, fromMirror)

        // Rung 4 — nothing. Garbage provider rows are deliberately NOT shown as a consolation:
        // the wa12 shape rendered a fully empty visible guide anyway, and rows that bracket
        // nothing mislabel every cell they'd fill.
        return Resolution(Source.NONE, emptyList())
    }

    /**
     * [resolve] + the bookkeeping every caller wants: read the remembered source first, record
     * which rung answered after. One entry point so the guide wirings on every platform carry
     * the same memory contract instead of three hand-rolled copies.
     */
    suspend fun resolveAndRemember(
        memory: Memory,
        accountId: String,
        streamId: Int,
        nowMs: Long,
        manual: ManualResolver? = null,
        provider: suspend () -> List<XtreamProgram>,
        mirror: suspend () -> List<XtreamProgram>,
    ): Resolution {
        val resolution = resolve(
            nowMs = nowMs,
            remembered = memory.rememberedFor(accountId, streamId),
            manual = manual?.let { { it.programmesFor(accountId, streamId, nowMs) } },
            provider = provider,
            mirror = mirror,
        )
        memory.remember(accountId, streamId, resolution.source)
        return resolution
    }

    /** How many channels the session memory may hold before insertion-order eviction. */
    const val MEMORY_CAP = 2_000

    /** Per-account counts of which rung is feeding the guide — the settings coverage line. */
    data class Tally(val manual: Int, val provider: Int, val mirror: Int, val none: Int) {
        val total: Int get() = manual + provider + mirror + none
    }

    /**
     * Session-scoped memory of which rung answered per (account, channel), so a channel that
     * fell to the mirror doesn't re-ask the panel on every focus. Same idiom as the Stalker
     * client's rowCache: a plain capped map keyed by account-prefixed ids, confined to the main
     * dispatcher the guide resolves on, never persisted — a fresh launch re-measures.
     *
     * Every outcome is recorded (the coverage tally wants the NONE count too), but only MIRROR
     * changes routing: a remembered NONE must not pin a channel empty for the session — the
     * transient panel failure that produced it deserves a retry on the next focus.
     */
    class Memory(private val cap: Int = MEMORY_CAP) {

        private val sources = mutableMapOf<String, Source>()   // LinkedHashMap: insertion-ordered

        fun rememberedFor(accountId: String, streamId: Int): Source? = sources[key(accountId, streamId)]

        fun remember(accountId: String, streamId: Int, source: Source) {
            val key = key(accountId, streamId)
            if (key !in sources && sources.size >= cap) sources.remove(sources.keys.first())
            sources[key] = source
        }

        /** The account's offset changed, or its mapping was rebuilt: measured sources are stale. */
        fun forgetAccount(accountId: String) {
            sources.keys.removeAll { it.startsWith("$accountId|") }
        }

        fun tally(accountId: String): Tally {
            var manual = 0; var provider = 0; var mirror = 0; var none = 0
            val prefix = "$accountId|"
            for ((key, source) in sources) {
                if (!key.startsWith(prefix)) continue
                when (source) {
                    Source.MANUAL -> manual++
                    Source.PROVIDER -> provider++
                    Source.MIRROR -> mirror++
                    Source.NONE -> none++
                }
            }
            return Tally(manual, provider, mirror, none)
        }

        private fun key(accountId: String, streamId: Int) = "$accountId|$streamId"
    }

    /**
     * The app-session memory the guide wiring and the settings coverage line share. Tests
     * construct their own [Memory] — this instance exists so the two surfaces agree without a
     * DI seam neither platform has for pure policy objects (the [IptvPanelGuard] precedent).
     */
    val sessionMemory: Memory = Memory()
}
