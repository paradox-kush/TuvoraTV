package com.nuvio.tv.playback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateMachineTest {
    @Test
    fun `valid startup follows the deterministic state sequence`() {
        var state = PlaybackMachineState()
        state = reduce(state, PlaybackCommand.SurfaceAvailable)

        val tune = PlaybackStateMachine.reduce(state, PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN))
        assertEquals(PlaybackState.RESOLVING, tune.state.snapshot.state)
        assertEquals(1, tune.state.snapshot.generation)
        assertAction<PlaybackAction.ResolveRequest>(tune)

        val resolved = PlaybackStateMachine.reduce(
            tune.state,
            PlaybackEvent.RequestResolved(1, liveRequest.summary(), StreamEvidence()),
        )
        assertEquals(PlaybackState.SELECTING_GRAPH, resolved.state.snapshot.state)
        assertAction<PlaybackAction.SelectPrimaryGraph>(resolved)

        val selected = PlaybackStateMachine.reduce(resolved.state, PlaybackEvent.GraphSelected(1, media3Graph))
        assertEquals(PlaybackState.ATTACHING_SURFACE, selected.state.snapshot.state)
        assertAction<PlaybackAction.AttachSurface>(selected)

        val attached = PlaybackStateMachine.reduce(selected.state, PlaybackEvent.SurfaceAttached(1))
        assertEquals(PlaybackState.STARTING_PRIMARY, attached.state.snapshot.state)
        assertAction<PlaybackAction.StartGraph>(attached)

        val playing = PlaybackStateMachine.reduce(attached.state, PlaybackEvent.FirstVideoFrame(1))
        assertEquals(PlaybackState.PLAYING, playing.state.snapshot.state)
        assertTrue(playing.state.snapshot.isPlaying)
        assertEquals(StreamAvailability.Available, playing.state.snapshot.streamAvailability)
    }

    @Test
    fun `invalid transition is a strict no-op`() {
        val initial = PlaybackMachineState()
        val invalid = PlaybackStateMachine.reduce(
            initial,
            PlaybackEvent.RequestResolved(0, liveRequest.summary(), StreamEvidence()),
        )

        assertSame(initial, invalid.state)
        assertTrue(invalid.actions.isEmpty())
    }

    @Test
    fun `same URL tune increments generation and stale asynchronous result is ignored`() {
        val first = PlaybackStateMachine.reduce(
            PlaybackMachineState(),
            PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN),
        ).state
        val second = PlaybackStateMachine.reduce(
            first,
            PlaybackCommand.Zap(liveRequest, SessionProfile.FULLSCREEN),
        )

        assertEquals(2, second.state.snapshot.generation)
        assertAction<PlaybackAction.ReleaseActiveWork>(second)

        val stale = PlaybackStateMachine.reduce(
            second.state,
            PlaybackEvent.RequestResolved(1, liveRequest.summary(), StreamEvidence()),
        )
        assertSame(second.state, stale.state)
        assertTrue(stale.actions.isEmpty())
    }

    @Test
    fun `rapid zap coalesces newest request behind one release epoch`() {
        val playing = playingState(ContentType.LIVE)
        val requestTwo = PlaybackRequest("https://example.invalid/two", contentType = ContentType.LIVE)
        val requestThree = PlaybackRequest("https://example.invalid/three", contentType = ContentType.LIVE)

        val firstZap = PlaybackStateMachine.reduce(
            playing,
            PlaybackCommand.Zap(requestTwo, SessionProfile.FULLSCREEN),
        )
        val barrier = assertAction<PlaybackAction.ReleaseActiveWork>(firstZap)

        val secondZap = PlaybackStateMachine.reduce(
            firstZap.state,
            PlaybackCommand.Zap(requestThree, SessionProfile.FULLSCREEN),
        )
        assertEquals(3, secondZap.state.snapshot.generation)
        assertEquals(barrier.releaseEpoch, secondZap.state.activeReleaseEpoch)
        assertTrue(secondZap.actions.isEmpty())
        assertSame(requestThree, secondZap.state.request)

        val wrongEpoch = PlaybackStateMachine.reduce(
            secondZap.state,
            PlaybackReducerInput.BarrierCompleted(barrier.releaseEpoch + 1),
        )
        assertSame(secondZap.state, wrongEpoch.state)
        assertTrue(wrongEpoch.actions.isEmpty())

        val completed = PlaybackStateMachine.reduce(
            secondZap.state,
            PlaybackReducerInput.BarrierCompleted(barrier.releaseEpoch),
        )
        assertEquals(PlaybackState.RESOLVING, completed.state.snapshot.state)
        val resolve = assertAction<PlaybackAction.ResolveRequest>(completed)
        assertEquals(3, resolve.generation)
        assertSame(requestThree, resolve.request)
    }

    @Test
    fun `new request waits for old graph release before resolution`() {
        val playing = playingState(ContentType.LIVE)
        val zap = PlaybackStateMachine.reduce(
            playing,
            PlaybackCommand.Zap(
                PlaybackRequest("https://example.invalid/other", contentType = ContentType.LIVE),
                SessionProfile.FULLSCREEN,
            ),
        )

        assertEquals(2, zap.state.snapshot.generation)
        assertEquals(PlaybackState.RELEASING, zap.state.snapshot.state)
        assertEquals(1, zap.actions.size)
        assertTrue(zap.actions.single() is PlaybackAction.ReleaseActiveWork)
        assertFalse(zap.actions.any { it is PlaybackAction.ResolveRequest })

        val engineOnly = PlaybackStateMachine.reduce(zap.state, PlaybackEvent.EngineReleased(1))
        assertSame(zap.state, engineOnly.state)
        assertTrue(engineOnly.actions.isEmpty())

        val released = completeBarrier(zap.state)
        assertEquals(PlaybackState.RESOLVING, released.state.snapshot.state)
        assertAction<PlaybackAction.ResolveRequest>(released)
    }

    @Test
    fun `only one recovery action is emitted for duplicate failure callbacks`() {
        val playing = playingState(ContentType.VOD)
        val failure = failure(Retryability.RETRYABLE_IN_PLACE)

        val first = PlaybackStateMachine.reduce(playing, PlaybackEvent.Failed(1, failure))
        assertEquals(PlaybackState.RECOVERING_IN_PLACE, first.state.snapshot.state)
        assertAction<PlaybackAction.RecoverInPlace>(first)

        val duplicate = PlaybackStateMachine.reduce(first.state, PlaybackEvent.Failed(1, failure))
        assertSame(first.state, duplicate.state)
        assertTrue(duplicate.actions.isEmpty())
        assertTrue(first.state.incident?.recoveryIssued == true)
    }

    @Test
    fun `failed VOD recovery releases active work before terminal failure`() {
        val recovering = PlaybackStateMachine.reduce(
            playingState(ContentType.VOD),
            PlaybackEvent.Failed(1, failure(Retryability.RETRYABLE_IN_PLACE)),
        ).state

        val failed = PlaybackStateMachine.reduce(
            recovering,
            PlaybackReducerInput.RecoveryAttemptFailed(1, failure(Retryability.FATAL)),
        )

        assertEquals(PlaybackState.RELEASING, failed.state.snapshot.state)
        assertEquals(AfterRelease.FAIL, failed.state.afterRelease)
        assertAction<PlaybackAction.ReleaseActiveWork>(failed)
        assertEquals(PlaybackState.FAILED, completeBarrier(failed.state).state.snapshot.state)
    }

    @Test
    fun `one handoff is allowed per incident and alternate failure cannot ping pong`() {
        val playing = playingState(ContentType.VOD)
        val handoffFailure = failure(Retryability.HANDOFF_ELIGIBLE)

        val begin = PlaybackStateMachine.reduce(playing, PlaybackEvent.Failed(1, handoffFailure))
        assertEquals(PlaybackState.HANDING_OFF_ONCE, begin.state.snapshot.state)
        val release = assertAction<PlaybackAction.ReleaseActiveWork>(begin)
        assertEquals(ActiveWorkReleaseReason.HANDOFF, release.reason)

        val duplicate = PlaybackStateMachine.reduce(begin.state, PlaybackEvent.Failed(1, handoffFailure))
        assertTrue(duplicate.actions.isEmpty())

        val released = completeBarrier(begin.state)
        assertAction<PlaybackAction.SelectHandoffGraph>(released)
        val selected = PlaybackStateMachine.reduce(released.state, PlaybackEvent.GraphSelected(1, mpvGraph))
        val attached = PlaybackStateMachine.reduce(
            PlaybackStateMachine.reduce(selected.state, PlaybackCommand.SurfaceAvailable).state,
            PlaybackEvent.SurfaceAttached(1),
        )
        val alternateFailure = PlaybackStateMachine.reduce(
            attached.state,
            PlaybackEvent.Failed(1, handoffFailure),
        )

        assertEquals(PlaybackState.RELEASING, alternateFailure.state.snapshot.state)
        assertAction<PlaybackAction.ReleaseActiveWork>(alternateFailure)
        assertTrue(alternateFailure.state.incident?.handoffIssued == true)
        assertEquals(PlaybackState.FAILED, completeBarrier(alternateFailure.state).state.snapshot.state)
    }

    @Test
    fun `live EOF reconnects while active and duplicate EOF does not schedule twice`() {
        val playing = playingState(ContentType.LIVE)
        val ended = PlaybackStateMachine.reduce(
            playing,
            PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF),
        )

        assertEquals(PlaybackState.LIVE_RECONNECTING, ended.state.snapshot.state)
        assertTrue(ended.state.snapshot.isReconnecting)
        assertAction<PlaybackAction.StartLiveReconnectLoop>(ended)

        val duplicate = PlaybackStateMachine.reduce(
            ended.state,
            PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF),
        )
        assertTrue(duplicate.actions.isEmpty())
    }

    @Test
    fun `failed live reconnect attempts stay inside one indefinite session loop`() {
        val reconnecting = PlaybackStateMachine.reduce(
            playingState(ContentType.LIVE),
            PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF),
        ).state

        val attemptFailure = PlaybackStateMachine.reduce(
            reconnecting,
            PlaybackEvent.Failed(1, failure(Retryability.RETRYABLE_WITH_FRESH_REQUEST)),
        )

        assertSame(reconnecting, attemptFailure.state)
        assertEquals(PlaybackState.LIVE_RECONNECTING, attemptFailure.state.snapshot.state)
        assertTrue(attemptFailure.actions.isEmpty())
    }

    @Test
    fun `deterministic live reconnect failure can escalate into the one handoff`() {
        val reconnecting = PlaybackStateMachine.reduce(
            playingState(ContentType.LIVE),
            PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF),
        ).state
        val handoffFailure = failure(Retryability.HANDOFF_ELIGIBLE).copy(deterministic = true)

        val escalated = PlaybackStateMachine.reduce(
            reconnecting,
            PlaybackReducerInput.LiveReconnectEscalated(1, handoffFailure),
        )

        assertEquals(PlaybackState.HANDING_OFF_ONCE, escalated.state.snapshot.state)
        assertEquals(AfterRelease.HANDOFF, escalated.state.afterRelease)
        assertAction<PlaybackAction.ReleaseActiveWork>(escalated)
    }

    @Test
    fun `stopped and shutdown never reopen while error must use typed Failed event`() {
        listOf(PlaybackEndReason.STOPPED, PlaybackEndReason.SHUTDOWN).forEach { reason ->
            val ended = PlaybackStateMachine.reduce(
                playingState(ContentType.LIVE),
                PlaybackEvent.PlaybackEnded(1, reason),
            )
            assertEquals(PlaybackState.RELEASING, ended.state.snapshot.state)
            val release = assertAction<PlaybackAction.ReleaseActiveWork>(ended)
            assertEquals(ActiveWorkReleaseReason.STOP, release.reason)
            assertTrue(ended.actions.none { it is PlaybackAction.StartLiveReconnectLoop })
        }

        val playing = playingState(ContentType.LIVE)
        val error = PlaybackStateMachine.reduce(
            playing,
            PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.ERROR),
        )
        assertSame(playing, error.state)
        assertTrue(error.actions.isEmpty())
    }

    @Test
    fun `surface loss cancels reconnect work before waiting for a new surface`() {
        val reconnecting = PlaybackStateMachine.reduce(
            playingState(ContentType.LIVE),
            PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF),
        ).state

        val lost = PlaybackStateMachine.reduce(reconnecting, PlaybackCommand.SurfaceUnavailable)
        assertEquals(PlaybackState.RELEASING, lost.state.snapshot.state)
        val release = assertAction<PlaybackAction.ReleaseActiveWork>(lost)
        assertEquals(ActiveWorkReleaseReason.SURFACE_LOST, release.reason)

        val completed = completeBarrier(lost.state)
        assertEquals(PlaybackState.ATTACHING_SURFACE, completed.state.snapshot.state)
        assertFalse(completed.state.surfaceAvailable)
        assertTrue(completed.actions.isEmpty())
    }

    @Test
    fun `paused live EOF waits until resume before reconnecting`() {
        val playing = playingState(ContentType.LIVE)
        val paused = PlaybackStateMachine.reduce(playing, PlaybackCommand.Pause).state
        val ended = PlaybackStateMachine.reduce(
            paused,
            PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF),
        )
        assertEquals(PlaybackState.DEGRADED, ended.state.snapshot.state)
        assertTrue(ended.actions.isEmpty())

        val resumed = PlaybackStateMachine.reduce(ended.state, PlaybackCommand.Resume)
        assertEquals(PlaybackState.LIVE_RECONNECTING, resumed.state.snapshot.state)
        assertAction<PlaybackAction.StartLiveReconnectLoop>(resumed)
    }

    @Test
    fun `VOD EOF is completion and never recovery`() {
        val playing = playingState(ContentType.VOD)
        val ended = PlaybackStateMachine.reduce(
            playing,
            PlaybackEvent.PlaybackEnded(1, PlaybackEndReason.EOF),
        )

        assertEquals(PlaybackState.RELEASING, ended.state.snapshot.state)
        val release = assertAction<PlaybackAction.ReleaseActiveWork>(ended)
        assertEquals(ActiveWorkReleaseReason.COMPLETED, release.reason)
        assertTrue(ended.actions.none { it is PlaybackAction.RecoverInPlace || it is PlaybackAction.StartLiveReconnectLoop })

        val released = completeBarrier(ended.state)
        assertEquals(PlaybackState.STOPPED, released.state.snapshot.state)
    }

    @Test
    fun `preview failure does not establish terminal stream unavailability`() {
        val startingGuide = startingState(ContentType.LIVE, SessionProfile.GUIDE)
        val rendererFailure = PlaybackFailure(
            code = FailureCode.VIDEO_RENDERER_FAILED,
            domain = FailureDomain.VIDEO_RENDERER_SURFACE,
            phase = FailurePhase.ENGINE_START,
            retryability = Retryability.FATAL,
        )

        val failed = PlaybackStateMachine.reduce(
            startingGuide,
            PlaybackEvent.Failed(1, rendererFailure),
        )
        assertEquals(
            PreviewAvailability.Unavailable(PreviewUnavailableReason.GUIDE_RENDER_PATH_UNAVAILABLE),
            failed.state.snapshot.previewAvailability,
        )
        assertEquals(StreamAvailability.Unknown, failed.state.snapshot.streamAvailability)

        val explicitTerminal = PlaybackStateMachine.reduce(
            failed.state,
            PlaybackReducerInput.StreamAvailabilityChanged(
                generation = 1,
                availability = StreamAvailability.TerminallyUnavailable(
                    StreamUnavailableReason.NO_ELIGIBLE_GRAPH,
                    TerminalAvailabilityEvidence.ALL_ELIGIBLE_GRAPHS_EXHAUSTED,
                ),
                authority = TerminalAvailabilityAuthority.SESSION_POLICY,
            ),
        )
        assertTrue(explicitTerminal.state.snapshot.streamAvailability is StreamAvailability.TerminallyUnavailable)
    }

    @Test
    fun `all eligible graphs terminal evidence requires session policy authority`() {
        val playing = playingState(ContentType.LIVE)
        val unavailable = StreamAvailability.TerminallyUnavailable(
            StreamUnavailableReason.NO_ELIGIBLE_GRAPH,
            TerminalAvailabilityEvidence.ALL_ELIGIBLE_GRAPHS_EXHAUSTED,
        )

        val untrusted = PlaybackStateMachine.reduce(
            playing,
            PlaybackReducerInput.StreamAvailabilityChanged(
                generation = 1,
                availability = unavailable,
                authority = TerminalAvailabilityAuthority.SOURCE,
            ),
        )
        assertSame(playing, untrusted.state)
        assertTrue(untrusted.actions.isEmpty())

        val trusted = PlaybackStateMachine.reduce(
            playing,
            PlaybackReducerInput.StreamAvailabilityChanged(
                generation = 1,
                availability = unavailable,
                authority = TerminalAvailabilityAuthority.SESSION_POLICY,
            ),
        )
        assertEquals(PlaybackState.RELEASING, trusted.state.snapshot.state)
        assertEquals(AfterRelease.FAIL, trusted.state.afterRelease)
        assertAction<PlaybackAction.ReleaseActiveWork>(trusted)
        assertEquals(PlaybackState.FAILED, completeBarrier(trusted.state).state.snapshot.state)
    }

    @Test
    fun `fatal startup failure completes active work barrier before failed`() {
        val starting = startingState(ContentType.VOD, SessionProfile.FULLSCREEN)
        val fatal = PlaybackStateMachine.reduce(
            starting,
            PlaybackEvent.Failed(1, failure(Retryability.FATAL)),
        )

        assertEquals(PlaybackState.RELEASING, fatal.state.snapshot.state)
        assertEquals(AfterRelease.FAIL, fatal.state.afterRelease)
        assertAction<PlaybackAction.ReleaseActiveWork>(fatal)
        assertEquals(PlaybackState.FAILED, completeBarrier(fatal.state).state.snapshot.state)
    }

    @Test
    fun `release is accepted from every startup phase and ignores later startup callback`() {
        val states = startupStates()
        states.forEach { startup ->
            val release = PlaybackStateMachine.reduce(startup, PlaybackCommand.Release)
            assertEquals("from ${startup.snapshot.state}", PlaybackState.RELEASING, release.state.snapshot.state)
            assertAction<PlaybackAction.ReleaseActiveWork>(release)

            val late = PlaybackStateMachine.reduce(
                release.state,
                PlaybackEvent.RequestResolved(1, liveRequest.summary(), StreamEvidence()),
            )
            assertSame(release.state, late.state)

            val stopped = completeBarrier(release.state)
            assertEquals(PlaybackState.STOPPED, stopped.state.snapshot.state)
        }
    }

    @Test
    fun `lifecycle inactive releases playing work and active resumes exactly once`() {
        val playing = playingState(ContentType.LIVE)
        val inactive = PlaybackStateMachine.reduce(
            playing,
            PlaybackReducerInput.LifecycleChanged(active = false),
        )
        assertFalse(inactive.state.lifecycleActive)
        assertEquals(AfterRelease.SUSPEND, inactive.state.afterRelease)
        assertEquals(LifecycleResume.REBUILD_CURRENT_GRAPH, inactive.state.lifecycleResume)
        val release = assertAction<PlaybackAction.ReleaseActiveWork>(inactive)
        assertEquals(ActiveWorkReleaseReason.LIFECYCLE_INACTIVE, release.reason)

        val suspended = completeBarrier(inactive.state)
        assertEquals(PlaybackState.STOPPED, suspended.state.snapshot.state)
        assertTrue(suspended.actions.isEmpty())

        val active = PlaybackStateMachine.reduce(
            suspended.state,
            PlaybackReducerInput.LifecycleChanged(active = true),
        )
        assertEquals(PlaybackState.ATTACHING_SURFACE, active.state.snapshot.state)
        assertAction<PlaybackAction.AttachSurface>(active)
        assertEquals(LifecycleResume.NONE, active.state.lifecycleResume)

        val duplicate = PlaybackStateMachine.reduce(
            active.state,
            PlaybackReducerInput.LifecycleChanged(active = true),
        )
        assertSame(active.state, duplicate.state)
        assertTrue(duplicate.actions.isEmpty())
    }

    @Test
    fun `lifecycle active before barrier completion resumes only after cancellation is complete`() {
        val inactive = PlaybackStateMachine.reduce(
            playingState(ContentType.LIVE),
            PlaybackReducerInput.LifecycleChanged(active = false),
        ).state
        val earlyActive = PlaybackStateMachine.reduce(
            inactive,
            PlaybackReducerInput.LifecycleChanged(active = true),
        )
        assertTrue(earlyActive.actions.isEmpty())
        assertTrue(earlyActive.state.activeReleaseEpoch != null)

        val completed = completeBarrier(earlyActive.state)
        assertEquals(PlaybackState.ATTACHING_SURFACE, completed.state.snapshot.state)
        assertAction<PlaybackAction.AttachSurface>(completed)
        assertEquals(LifecycleResume.NONE, completed.state.lifecycleResume)
    }

    @Test
    fun `lifecycle inactive during resolution cancels then reruns resolution once`() {
        val resolving = startupStates()[0]
        val inactive = PlaybackStateMachine.reduce(
            resolving,
            PlaybackReducerInput.LifecycleChanged(active = false),
        )
        assertEquals(LifecycleResume.RESOLVE_REQUEST, inactive.state.lifecycleResume)
        assertAction<PlaybackAction.ReleaseActiveWork>(inactive)

        val suspended = completeBarrier(inactive.state)
        val active = PlaybackStateMachine.reduce(
            suspended.state,
            PlaybackReducerInput.LifecycleChanged(active = true),
        )
        assertEquals(PlaybackState.RESOLVING, active.state.snapshot.state)
        assertAction<PlaybackAction.ResolveRequest>(active)
    }

    @Test
    fun `explicit stop while lifecycle suspended prevents later resume`() {
        val inactive = PlaybackStateMachine.reduce(
            playingState(ContentType.LIVE),
            PlaybackReducerInput.LifecycleChanged(active = false),
        ).state
        val suspended = completeBarrier(inactive).state

        val stopped = PlaybackStateMachine.reduce(suspended, PlaybackCommand.Stop)
        assertEquals(LifecycleResume.NONE, stopped.state.lifecycleResume)
        assertFalse(stopped.state.sessionActive)

        val active = PlaybackStateMachine.reduce(
            stopped.state,
            PlaybackReducerInput.LifecycleChanged(active = true),
        )
        assertTrue(active.actions.isEmpty())
        assertEquals(PlaybackState.STOPPED, active.state.snapshot.state)
    }

    @Test
    fun `retryable pre-graph failures rerun their startup phase`() {
        val (resolving, selecting, attaching) = startupStates()

        val resolveRetry = PlaybackStateMachine.reduce(
            resolving,
            PlaybackEvent.Failed(1, failure(Retryability.RETRYABLE_IN_PLACE)),
        )
        assertEquals(PlaybackState.RESOLVING, resolveRetry.state.snapshot.state)
        assertAction<PlaybackAction.ResolveRequest>(resolveRetry)
        assertTrue(resolveRetry.actions.none {
            it is PlaybackAction.RecoverInPlace || it is PlaybackAction.StartLiveReconnectLoop
        })

        val selectRetry = PlaybackStateMachine.reduce(
            selecting,
            PlaybackEvent.Failed(1, failure(Retryability.RETRYABLE_IN_PLACE)),
        )
        assertEquals(PlaybackState.SELECTING_GRAPH, selectRetry.state.snapshot.state)
        assertAction<PlaybackAction.SelectPrimaryGraph>(selectRetry)

        val attachRetry = PlaybackStateMachine.reduce(
            PlaybackStateMachine.reduce(attaching, PlaybackCommand.SurfaceAvailable).state,
            PlaybackEvent.Failed(1, failure(Retryability.RETRYABLE_IN_PLACE)),
        )
        assertEquals(PlaybackState.ATTACHING_SURFACE, attachRetry.state.snapshot.state)
        assertAction<PlaybackAction.AttachSurface>(attachRetry)
    }

    @Test
    fun `fresh request startup failure returns to resolution`() {
        val selecting = startupStates()[1]
        val retry = PlaybackStateMachine.reduce(
            selecting,
            PlaybackEvent.Failed(1, failure(Retryability.RETRYABLE_WITH_FRESH_REQUEST)),
        )

        assertEquals(PlaybackState.RESOLVING, retry.state.snapshot.state)
        assertAction<PlaybackAction.ResolveRequest>(retry)
        assertTrue(retry.actions.none { it is PlaybackAction.RecoverInPlace })
    }

    @Test
    fun `failed guide preview can reselect for fullscreen`() {
        val guide = startingState(ContentType.LIVE, SessionProfile.GUIDE)
        val failure = PlaybackFailure(
            code = FailureCode.VIDEO_RENDERER_FAILED,
            domain = FailureDomain.VIDEO_RENDERER_SURFACE,
            phase = FailurePhase.ENGINE_START,
            retryability = Retryability.FATAL,
        )
        val failing = PlaybackStateMachine.reduce(guide, PlaybackEvent.Failed(1, failure))
        val failed = completeBarrier(failing.state).state
        assertEquals(PlaybackState.FAILED, failed.snapshot.state)
        assertEquals(StreamAvailability.Unknown, failed.snapshot.streamAvailability)

        val requested = PlaybackStateMachine.reduce(
            failed,
            PlaybackCommand.SessionProfileChanged(SessionProfile.FULLSCREEN),
        )
        val resolve = assertAction<PlaybackAction.ResolveProfileChange>(requested)
        val fullscreen = PlaybackStateMachine.reduce(
            requested.state,
            PlaybackReducerInput.RequirementsChangeResolved(
                changeId = 1,
                generation = 1,
                previousProfile = resolve.previousProfile,
                targetProfile = SessionProfile.FULLSCREEN,
                requirements = requirements(SessionProfile.FULLSCREEN),
                impact = ChangeImpact.RESELECT_GRAPH,
            ),
        )
        assertEquals(PlaybackState.SELECTING_GRAPH, fullscreen.state.snapshot.state)
        val select = assertAction<PlaybackAction.SelectPrimaryGraph>(fullscreen)
        assertEquals(SessionProfile.FULLSCREEN, select.profile)
    }

    @Test
    fun `profile change applies in place without graph restart`() {
        val playing = playingState(ContentType.LIVE)
        val requested = PlaybackStateMachine.reduce(
            playing,
            PlaybackCommand.SessionProfileChanged(SessionProfile.GUIDE),
        )
        val resolve = assertAction<PlaybackAction.ResolveProfileChange>(requested)
        val changed = PlaybackStateMachine.reduce(
            requested.state,
            PlaybackReducerInput.RequirementsChangeResolved(
                changeId = 1,
                generation = 1,
                previousProfile = resolve.previousProfile,
                targetProfile = SessionProfile.GUIDE,
                requirements = requirements(SessionProfile.GUIDE),
                impact = ChangeImpact.APPLY_IN_PLACE,
            ),
        )

        assertEquals(PlaybackState.PLAYING, changed.state.snapshot.state)
        assertEquals(SessionProfile.GUIDE, changed.state.snapshot.profile)
        assertAction<PlaybackAction.ApplyRequirementsInPlace>(changed)
    }

    @Test
    fun `rejected profile requirements restore prior profile without stopping playback`() {
        val playing = playingState(ContentType.LIVE)
        val requested = PlaybackStateMachine.reduce(
            playing,
            PlaybackCommand.SessionProfileChanged(SessionProfile.GUIDE),
        )

        val rejected = PlaybackStateMachine.reduce(
            requested.state,
            PlaybackReducerInput.RequirementsChangeRejected(
                generation = 1,
                previousProfile = SessionProfile.FULLSCREEN,
            ),
        )

        assertEquals(PlaybackState.PLAYING, rejected.state.snapshot.state)
        assertEquals(SessionProfile.FULLSCREEN, rejected.state.snapshot.profile)
        assertTrue(rejected.actions.isEmpty())
    }

    @Test
    fun `profile rebuild waits for release then rebuilds the same graph`() {
        val playing = playingState(ContentType.LIVE)
        val requested = PlaybackStateMachine.reduce(
            playing,
            PlaybackCommand.SessionProfileChanged(SessionProfile.GUIDE),
        )
        val resolve = assertAction<PlaybackAction.ResolveProfileChange>(requested)
        val changed = PlaybackStateMachine.reduce(
            requested.state,
            PlaybackReducerInput.RequirementsChangeResolved(
                changeId = 1,
                generation = 1,
                previousProfile = resolve.previousProfile,
                targetProfile = SessionProfile.GUIDE,
                requirements = requirements(SessionProfile.GUIDE),
                impact = ChangeImpact.REBUILD_CURRENT_GRAPH,
            ),
        )
        assertEquals(PlaybackState.RELEASING, changed.state.snapshot.state)
        assertAction<PlaybackAction.ReleaseActiveWork>(changed)

        val released = completeBarrier(changed.state)
        assertEquals(PlaybackState.ATTACHING_SURFACE, released.state.snapshot.state)
        assertEquals(media3Graph, released.state.snapshot.graph)
        assertAction<PlaybackAction.AttachSurface>(released)
    }

    @Test
    fun `profile reselect waits for release then runs policy selection`() {
        val playing = playingState(ContentType.LIVE)
        val requested = PlaybackStateMachine.reduce(
            playing,
            PlaybackCommand.SessionProfileChanged(SessionProfile.GUIDE),
        )
        val resolve = assertAction<PlaybackAction.ResolveProfileChange>(requested)
        val changed = PlaybackStateMachine.reduce(
            requested.state,
            PlaybackReducerInput.RequirementsChangeResolved(
                changeId = 1,
                generation = 1,
                previousProfile = resolve.previousProfile,
                targetProfile = SessionProfile.GUIDE,
                requirements = requirements(SessionProfile.GUIDE),
                impact = ChangeImpact.RESELECT_GRAPH,
            ),
        )
        assertEquals(PlaybackState.RELEASING, changed.state.snapshot.state)

        val released = completeBarrier(changed.state)
        assertEquals(PlaybackState.SELECTING_GRAPH, released.state.snapshot.state)
        val select = assertAction<PlaybackAction.SelectPrimaryGraph>(released)
        assertEquals(SessionProfile.GUIDE, select.profile)
    }

    @Test
    fun `pause during startup is carried into StartGraph`() {
        var state = PlaybackStateMachine.reduce(
            PlaybackMachineState(),
            PlaybackCommand.SurfaceAvailable,
        ).state
        state = PlaybackStateMachine.reduce(
            state,
            PlaybackCommand.Tune(liveRequest, SessionProfile.FULLSCREEN),
        ).state
        state = PlaybackStateMachine.reduce(state, PlaybackCommand.Pause).state
        state = PlaybackStateMachine.reduce(
            state,
            PlaybackEvent.RequestResolved(1, liveRequest.summary(), StreamEvidence()),
        ).state
        state = PlaybackStateMachine.reduce(state, PlaybackEvent.GraphSelected(1, media3Graph)).state

        val attached = PlaybackStateMachine.reduce(state, PlaybackEvent.SurfaceAttached(1))
        val start = assertAction<PlaybackAction.StartGraph>(attached)
        assertTrue(start.startPaused)
        assertTrue(attached.state.paused)
    }

    private fun playingState(contentType: ContentType): PlaybackMachineState {
        var state = startingState(contentType, SessionProfile.FULLSCREEN)
        state = PlaybackStateMachine.reduce(
            state,
            PlaybackEvent.TracksAvailable(1, hasVideo = true, audioTrackCount = 1, subtitleTrackCount = 0),
        ).state
        return PlaybackStateMachine.reduce(state, PlaybackEvent.FirstVideoFrame(1)).state
    }

    private fun startingState(
        contentType: ContentType,
        profile: SessionProfile,
    ): PlaybackMachineState {
        val request = PlaybackRequest("https://example.invalid/stream", contentType = contentType)
        var state = PlaybackStateMachine.reduce(
            PlaybackMachineState(),
            PlaybackCommand.SurfaceAvailable,
        ).state
        state = PlaybackStateMachine.reduce(state, PlaybackCommand.Tune(request, profile)).state
        state = PlaybackStateMachine.reduce(
            state,
            PlaybackEvent.RequestResolved(1, request.summary(), StreamEvidence()),
        ).state
        state = PlaybackStateMachine.reduce(state, PlaybackEvent.GraphSelected(1, media3Graph)).state
        return PlaybackStateMachine.reduce(state, PlaybackEvent.SurfaceAttached(1)).state
    }

    private fun startupStates(): List<PlaybackMachineState> {
        val request = liveRequest
        val resolving = PlaybackStateMachine.reduce(
            PlaybackMachineState(),
            PlaybackCommand.Tune(request, SessionProfile.FULLSCREEN),
        ).state
        val selecting = PlaybackStateMachine.reduce(
            resolving,
            PlaybackEvent.RequestResolved(1, request.summary(), StreamEvidence()),
        ).state
        val attaching = PlaybackStateMachine.reduce(
            selecting,
            PlaybackEvent.GraphSelected(1, media3Graph),
        ).state
        val starting = PlaybackStateMachine.reduce(
            PlaybackStateMachine.reduce(attaching, PlaybackCommand.SurfaceAvailable).state,
            PlaybackEvent.SurfaceAttached(1),
        ).state
        return listOf(resolving, selecting, attaching, starting)
    }

    private fun reduce(state: PlaybackMachineState, command: PlaybackCommand): PlaybackMachineState =
        PlaybackStateMachine.reduce(state, command).state

    private fun completeBarrier(state: PlaybackMachineState): PlaybackTransition =
        PlaybackStateMachine.reduce(
            state,
            PlaybackReducerInput.BarrierCompleted(requireNotNull(state.activeReleaseEpoch)),
        )

    private fun failure(retryability: Retryability) = PlaybackFailure(
        code = FailureCode.NO_PROGRESS,
        domain = FailureDomain.VIDEO_RENDERER_SURFACE,
        phase = FailurePhase.PLAYBACK,
        retryability = retryability,
    )

    private inline fun <reified T : PlaybackAction> assertAction(transition: PlaybackTransition): T {
        val matches = transition.actions.filterIsInstance<T>()
        assertEquals("Expected one ${T::class.simpleName}: ${transition.actions}", 1, matches.size)
        return matches.single()
    }

    private companion object {
        val liveRequest = PlaybackRequest("https://example.invalid/live", contentType = ContentType.LIVE)

        val media3Graph = PlaybackGraph(
            id = "media3-primary",
            engine = EngineType.MEDIA3,
            outputProfile = GraphOutputProfile.MEDIA3_STANDARD,
            decoderMode = DecoderMode.HARDWARE,
            audioMode = AudioMode.DECODE,
            surfaceMode = SurfaceMode.SURFACE_VIEW,
        )

        val mpvGraph = PlaybackGraph(
            id = "mpv-handoff",
            engine = EngineType.LIBMPV,
            outputProfile = GraphOutputProfile.MPV_RENDER,
            decoderMode = DecoderMode.HARDWARE,
            audioMode = AudioMode.DECODE,
            surfaceMode = SurfaceMode.GPU_RENDER,
        )

        fun requirements(profile: SessionProfile) = PlaybackRequirements(
            profile = profile,
            priority = if (profile == SessionProfile.GUIDE) {
                SessionPriority.STARTUP_SPEED
            } else {
                SessionPriority.QUALITY_AND_STABILITY
            },
            qualityIntent = if (profile == SessionProfile.GUIDE) {
                VideoQualityIntent.PREVIEW
            } else {
                VideoQualityIntent.FULL
            },
            displayModeSwitchAllowed = profile == SessionProfile.FULLSCREEN,
            frameRatePreference = FrameRatePreference.OFF,
            hdrPreference = HdrPreference.AUTO,
            decoderPreference = DecoderPreference.AUTO,
            softwareDecodeFallbackAllowed = false,
            subtitleFidelity = SubtitleFidelity.COMPATIBLE,
            subtitlesEnabled = false,
            audioOutput = AudioOutputPreference.AUTO,
            pcmProcessingAllowed = true,
            buffering = BufferingPreference.RECOMMENDED,
            gpuRenderingAllowed = false,
            eligibleEngines = setOf(EngineType.MEDIA3),
            secureOutputRequired = false,
            resourceBudget = ResourceBudget(),
        )
    }
}
