# Clean playback output / AFR core contract — 2026-08-27

## Scope

This change is TV-only and defines the engine-neutral display-output contract. It does not wire a
production route, read a provider URL, inspect credentials, or depend on Media3/libmpv types.
Android Window/display execution and engine fact propagation are separate adapter work.

Current real-device certification scope is ONN/Amlogic only. Fire TV is deferred and untested; it
must not be represented as passed by this work.

## Decisions

- The clean frame-rate modes are `OFF`, `ON_START`, and `ON_RATE_CHANGE`.
- Persisted V1 names `ON_COMMITTED_PLAYBACK` and `ALWAYS` migrate once to the explicit V2 names.
  Deprecated source aliases remain temporarily so independently migrating adapter tests compile.
- Legacy `START` and `START_STOP` both import as `ON_START`. Clean V1 always relinquishes display
  ownership on a terminal session release; it does not preserve the legacy sticky-start behavior.
- Runtime output is gated by a first committed video frame. Format/size callbacks alone never
  switch the display during rapid tune or graph startup.
- Runtime facts carry a monotonic per-generation revision that also advances when an engine attempt
  restarts. A lower-revision request may never replace a newer factual output decision, even if
  coroutine scheduling changes Mutex acquisition order.
- Frame-rate facts are accepted only when finite and within 10–120 fps. Dimensions must be positive.
  Invalid, stale, or duplicate facts are ignored and no rate is synthesized.
- Missing rate, missing dimensions, unsupported output, no compatible mode, an unconfirmed Android
  mode request, and an optional Window request exception are typed nonfatal outcomes with sanitized
  diagnostics. Optional AFR/resolution matching can never terminate otherwise-playing Live TV.
  `PlaybackResult.Failure` is reserved for a hard correctness/security output constraint.
- AFR permission is independent of resolution matching. Resolution matching is retained explicitly
  in `PlaybackRequirements`; GUIDE sessions keep display switching disabled.
- The session serializes output work and reports only a result matching the current generation,
  facts, and commit state. An asynchronous old result cannot overwrite the current output status.
- Reset receives the released graph generation, never the request waiting behind a release barrier.
  `STOP`, `REPLACE_REQUEST`, `COMPLETED`, `FAILURE`, and `LIFECYCLE_INACTIVE` relinquish output.
  `REBUILD`, `RESELECT`, `HANDOFF`, and `SURFACE_LOST` preserve it across internal graph work.

## Core data flow

```text
engine factual events
    -> PlaybackEvent.VideoFrameRateChanged / VideoSizeChanged
    -> PlaybackSnapshot.videoOutputFacts
    -> first committed frame
    -> PlaybackAction.ApplyPlaybackOutput
    -> PlaybackSession (serialized output mutex)
    -> PlaybackOutputController.apply(PlaybackOutputRequest)
    -> PlaybackOutputApplication
    -> PlaybackSnapshot.playbackOutputStatus
```

The output request contains only generation, effective requirements, factual video output data, and
commit state. It contains no URL, provider identity, headers, query string, or engine-specific type.

## Verification owned by this lane

- requirements keep AFR and resolution matching independent and classify display changes;
- reducer stores valid facts, rejects invalid/stale/duplicate facts, and gates application on commit;
- stale output completions cannot replace current status;
- session issues a pre-start waiting request and a committed factual request;
- replacement reset uses the released graph generation;
- preference schema migrates V1 frame-rate names and legacy modes deterministically.

Gradle verification is intentionally pending the repository-wide serialized lane release.

## Platform check

The symbols are under NuvioTV clean playback only. NuvioMobile, NuvioDesktop, `nuvio-backend`, and
`nuvio-web` have no Android TV display-mode output port or clean TV session reducer, so they are
genuinely unaffected.
