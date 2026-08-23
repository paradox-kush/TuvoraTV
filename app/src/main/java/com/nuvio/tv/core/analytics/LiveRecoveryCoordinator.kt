package com.nuvio.tv.core.analytics

/**
 * The single live-recovery owner (design §3.5 / §4): one serialized, ordered ladder that
 * classifies the fault and drives exactly one action, replacing today's racing recovery callbacks.
 *
 * This EXTENDS the existing pure policies rather than duplicating them (design §3.5):
 *  - the transient-vs-persistent split reuses [GuidePreviewFreezePolicy]'s stable-play window
 *    ([T_STABLE_MS] = its `MIN_STABLE_PLAYBACK_MS`) as the re-wedge reset;
 *  - the cheap connection-free resync reuses [LivePlaybackRecoveryPolicy.VIDEO_RESET_ATTEMPTS]
 *    (audio-alive ⇒ the fault is downstream of the connection, so touch the video pipeline only);
 *  - it ADDS the fault-family rungs the design calls for: `PROVIDER_LIMIT` terminal, `AUTH`
 *    fresh-link mint, and the **engine switch** rung that is the durable backward-PTS (7TV) fix —
 *    plus the live **one-connection budget** (§3.6) so a `max_connections=1` panel is never raced
 *    into an HTTP 509.
 *
 * The two fault families are never conflated (design principle 5): a **timestamp/clock fault**
 * (backward-PTS, recurring discontinuity) is *upstream of the decoder* and is fixed ONLY by a
 * clock-rebasing engine (libmpv) — never by another re-open on the same engine; a **transient
 * wedge** (broken GOP, one-off splice) is fixed by the cheap connection-free resync (~90% of the
 * fleet). Persistence is decided by the direct backward-jump signal first, the re-wedge count second.
 *
 * Pure: all state (attempts, re-wedge history, budget, current engine, per-channel memory) is
 * passed in, so every decision is pinned by tests without a player, a provider, or a device.
 * Lives in `core/analytics` (firewall-exempt) alongside the policies it extends.
 */
internal object LiveRecoveryCoordinator {

    /** The fault the detector/classifier resolved this incident to (design §4 rungs). */
    enum class Fault {
        /** 401/403/410/456 — the link died; mint a fresh single-use one (rung 1). */
        AUTH,
        /** HTTP 509 — provider concurrent-connection limit. Terminal (rung 1). */
        PROVIDER_LIMIT,
        /** A `.ts` answered as HLS (or vice-versa); force the other mime (rung 2, one-shot). */
        CONTAINER_MISMATCH,
        /** Picture froze while audio played, or the pipe stalled — the ~90% transient class
         *  (rung 3), escalating to persistent (rung 4) only if it re-wedges within [T_STABLE_MS]. */
        STALL,
        /** A decoder wedge (silent HW discard / macroblocking): cheap resync, then engine switch. */
        DECODER_WEDGE,
        /** A definitive backward-PTS discontinuity from the direct signal — straight to rung 4. */
        BACKWARD_PTS,
        /** The whole connection dropped (socket/network) — a budgeted re-open (rung 5). */
        NETWORK_DROP,
    }

    enum class Engine { EXO, MPV }

    sealed class Decision {
        /** Backoff/gate has not elapsed; leave the in-flight action alone. */
        data object Wait : Decision()

        /** Force the other container mime and re-prepare (rung 2). One-shot per channel; the
         *  caller's self-inflicted guard prevents a loop. Connection-reusing, so budget-free. */
        data object ForceOtherContainer : Decision()

        /** Connection-free resync — `seekToDefaultPosition()` (Exo live edge) / `loadfile replace`
         *  at live edge (mpv). No new HTTP connection, so it never burns a provider slot (rung 3). */
        data object ResyncConnectionFree : Decision()

        /** Mint a fresh single-use link and re-open at the live edge (rung 1, auth). Budgeted. */
        data object FreshLinkMint : Decision()

        /** Re-prepare the current stream (a budgeted re-open; rung 5). */
        data object Reconnect : Decision()

        /** Switch to [target] engine, preserving the live edge, and remember it for this channel
         *  (rung 4 — the durable backward-PTS fix). Budgeted-but-guaranteed (a reserved slot). */
        data class EngineSwitch(val target: Engine) : Decision()

        /** Stop and surface a specific, actionable error — never a silent black screen (rung 9). */
        data class GiveUp(val reason: GiveUpReason) : Decision()
    }

    enum class GiveUpReason {
        /** HTTP 509 — the provider's concurrent-connection limit; closing another device fixes it. */
        PROVIDER_LIMIT,
        /** Both engines wedged, or libmpv is not a viable target on this device — honest defeat. */
        DECODE_UNSUPPORTED,
        /** Ran out of budgeted re-opens for this incident. */
        ATTEMPTS_EXHAUSTED,
    }

    /**
     * A single backward content-time jump at or beyond this is a definitive persistent
     * discontinuity (design §1: ExoPlayer's `VideoRenderQualityTracker` logs "content time jumped
     * N ms backwards"; 7TV jumped 110 182 ms). Set well above normal live jitter and ad-splice
     * discontinuities so a single occurrence escalates straight to the engine switch, skipping the
     * wasted re-prepare cycles the RCA showed. Tunable.
     */
    const val BACKWARD_JUMP_THRESHOLD_MS = 3_000L

    /** Re-wedges of the same channel within [T_STABLE_MS] that mark it persistent (design §1
     *  fallback, K=2 ≈ 2× the observed 13.5 s 7TV cadence). */
    const val PERSISTENT_REWEDGE_COUNT = 2

    /** The clean-play window that resets the re-wedge counter. Matches
     *  `GuidePreviewFreezePolicy.MIN_STABLE_PLAYBACK_MS` (duplicated as a literal because
     *  `core/analytics` must not depend on `ui/screens/iptv`). */
    const val T_STABLE_MS = 30_000L

    /** Minimum time between re-opens (§3.6): ≥ the mpv `reconnect_delay_max`. Every re-open — a
     *  fresh-link mint, a re-prepare, and the engine-switch handoff — waits this, so a
     *  `max_connections=1` panel's slot has time to release and the re-open is not raced into a 509. */
    const val MIN_REOPEN_BACKOFF_MS = 5_000L

    /** Budgeted re-opens for a `max_connections=1` panel before giving up (StreamVault does exactly
     *  one). The engine switch is separate and guaranteed (review-C F3) — it is the fix, so the
     *  cap must not pre-empt it. */
    const val MAX_REOPENS_ONE_CONNECTION = 1

    data class Input(
        val fault: Fault,
        /** The engine currently playing. */
        val engine: Engine,
        /** BACKWARD_PTS/persistent engine-switch is live-scoped (review-C F6); catch-up has its
         *  own lane and preserves scrub position, not the live edge. */
        val isLiveFeed: Boolean,
        /** Step-0 gate: libmpv is a viable escalation target on this device (Onn = confirmed). */
        val mpvAvailable: Boolean,
        /** At most one engine switch per channel-dwell (§3.6a rule 5): if the switched-to engine
         *  also wedged, surface a specific error rather than ping-ponging (native/fd churn). */
        val engineSwitchedThisDwell: Boolean,
        /** Cheap connection-free resyncs already spent on THIS wedge incident. */
        val connectionFreeAttempts: Int,
        /** Direct signal: the largest backward content-time jump observed, or null if none. */
        val backwardJumpMs: Long?,
        /** Distinct re-wedges of this channel within [T_STABLE_MS] (fallback persistence signal). */
        val reWedgesInWindow: Int,
        /** Re-opens already spent this incident (fresh-link mint + re-prepare). */
        val reopensThisIncident: Int,
        /** Time since the last re-open (or [Long.MAX_VALUE] if none yet — the first is immediate). */
        val sinceLastReopenMs: Long,
        /** The provider caps concurrent connections at one — tighten the re-open budget. */
        val maxConnectionsOne: Boolean,
        val videoResetAttempts: Int = LivePlaybackRecoveryPolicy.VIDEO_RESET_ATTEMPTS,
    )

    fun evaluate(input: Input): Decision {
        // Rung 1 — the provider connection limit is terminal; never race it into another 509.
        if (input.fault == Fault.PROVIDER_LIMIT) return Decision.GiveUp(GiveUpReason.PROVIDER_LIMIT)

        // Rung 4 — persistent discontinuity: the ONLY durable fix is a clock-rebasing engine, never
        // another re-open on the same one (design §1). Live-scoped (F6). Checked before the cheap
        // rungs so the direct backward-jump signal skips the wasted re-prepare cycles (design §1).
        if (input.isLiveFeed && isPersistent(input)) return engineSwitchDecision(input)

        // Rung 2 — a container-mime mismatch is a connection-reusing re-prepare (budget-free); the
        // caller's one-shot self-inflicted guard stops it looping.
        if (input.fault == Fault.CONTAINER_MISMATCH) return Decision.ForceOtherContainer

        // Rung 1 — auth/link death: mint a fresh single-use link and re-open (budgeted).
        if (input.fault == Fault.AUTH) return reopen(input, Decision.FreshLinkMint)

        // Rung 3 — the ~90% transient class: connection-free resync first (audio-alive ⇒ the fault
        // is downstream of the connection), up to the video-reset budget, before spending a slot.
        if ((input.fault == Fault.STALL || input.fault == Fault.DECODER_WEDGE) &&
            input.connectionFreeAttempts < input.videoResetAttempts
        ) {
            return Decision.ResyncConnectionFree
        }

        // Rung 5 / generic — a budgeted re-open (backoff + cap; §3.6).
        return reopen(input, Decision.Reconnect)
    }

    /** Persistent = one large backward jump (direct signal) OR K re-wedges within T_stable. */
    fun isPersistent(input: Input): Boolean =
        input.fault == Fault.BACKWARD_PTS ||
            (input.backwardJumpMs != null && input.backwardJumpMs >= BACKWARD_JUMP_THRESHOLD_MS) ||
            input.reWedgesInWindow >= PERSISTENT_REWEDGE_COUNT

    private fun engineSwitchDecision(input: Input): Decision {
        // No ping-pong (§3.6a rule 5): if we are already on mpv, or already switched this dwell, the
        // robust engine also failed — surface a specific error, never churn back to ExoPlayer.
        if (input.engine == Engine.MPV || input.engineSwitchedThisDwell) {
            return Decision.GiveUp(GiveUpReason.DECODE_UNSUPPORTED)
        }
        // mpv must be a viable target on this device (step-0 gate); else the honest outcome.
        if (!input.mpvAvailable) return Decision.GiveUp(GiveUpReason.DECODE_UNSUPPORTED)
        // Budgeted-but-guaranteed (review-C F3): the switch is a re-open and the provider slot
        // releases asynchronously after teardown, so honour the ≥5 s handoff backoff — but the
        // connection cap must NOT pre-empt it, because the switch is the fix (a reserved slot).
        if (input.sinceLastReopenMs < MIN_REOPEN_BACKOFF_MS) return Decision.Wait
        return Decision.EngineSwitch(Engine.MPV)
    }

    private fun reopen(input: Input, action: Decision): Decision {
        // Inter-reopen backoff (§3.6): the first re-open (no prior, sinceLast = MAX) is immediate —
        // the freeze has already been visible — later ones wait so a slot can release.
        if (input.sinceLastReopenMs < MIN_REOPEN_BACKOFF_MS) return Decision.Wait
        val cap = if (input.maxConnectionsOne) MAX_REOPENS_ONE_CONNECTION else LivePlaybackRecoveryPolicy.MAX_ATTEMPTS
        if (input.reopensThisIncident >= cap) return Decision.GiveUp(GiveUpReason.ATTEMPTS_EXHAUSTED)
        return action
    }
}
