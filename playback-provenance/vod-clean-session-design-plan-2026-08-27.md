# VOD clean-session design plan — 2026-08-27

## Outcome

Preserve the current `PlayerScreen` experience while making the shared clean `PlaybackSession` the
only VOD playback authority.

```text
VOD UI intent
  -> VodPlaybackPresentationBridge
  -> PlaybackSessionController
  -> PlaybackSession actor
  -> requirements / policy / graph
  -> Media3Engine | MpvEngine
  -> generation-bound facts
  -> immutable VOD presentation state
  -> existing PlayerScreen
```

## Design authorities

### PlaybackSession

Owns generation, release-before-replace, request resolution, policy, graph, start/recovery/handoff,
failure taxonomy, watchdogs, lifecycle, EOF/terminal state, compatibility history and restoration
ordering. Recovery state belongs to the session generation, never the engine object.

### Engine adapters

Execute generation-bound commands and emit facts. They may attach/detach surfaces, start, pause,
seek, select tracks, attach subtitles, set supported in-place parameters, snapshot metrics and
release. They never retry, resolve a provider, select another graph, or reinterpret policy.

### VodPlaybackPresentationBridge

Maps existing `PlayerEvent` intent into typed presentation, feature or playback commands and maps
clean facts into the current `PlayerUiState`/timeline shapes. It contains no engine, network,
decoder, retry, provider or device policy. It emits one-shot navigation/external/report effects.

### Feature coordinators

- `VodSourceCoordinator`: addon/debrid/torrent/source/episode discovery and clean ingress creation.
- `VodSubtitleCoordinator`: addon subtitle discovery/cache and opaque attachment registration.
- `VodProgressScrobbleCoordinator`: resume load and lifecycle-bound progress/scrobble persistence.
- `VodEpisodeCoordinator`: episodes, post-play, autoplay and still-watching decisions.
- `VodPlaybackReportingCoordinator`: user reports from sanitized diagnostic facts.

These coordinators never construct or control Media3/libmpv.

### Generic playback host and surfaces

Extract content-neutral ownership from the current live host/surface coordinator. Keep thin Live and
VOD wrappers to enforce content semantics. `PlayerScreen` mounts a generic video-output container;
the host binds the active adapter surface privately.

## VOD state model

Extend the secret-safe snapshot with:

- `TimelineFacts(positionMs, durationMs, bufferedPositionMs, seekable)`;
- `TrackCatalog(audio, subtitles, selectedAudioId, selectedSubtitleId, subtitlesEnabled)`;
- `playbackRate`;
- `completionReason`;
- effective capability/availability for controls;
- existing graph/output/failure/status facts.

Track IDs and external-subtitle IDs are opaque generation-safe values. They are presentation-safe
but do not contain URLs or provider data.

## Command model

Core playback commands:

- Tune/replace request with explicit start-position policy;
- Pause, Resume, Retry, Stop, Release;
- SeekTo;
- SelectAudioTrack, SelectSubtitleTrack, DisableSubtitles;
- AttachExternalSubtitle using an opaque registry ID;
- SetPlaybackRate;
- preference change for audio/subtitle/HDR/AFR/decoder/engine requests.

UI preview scrubbing, panel visibility, focus, timers and browsing do not belong in the core.

## Recovery and restoration

Before a rebuild/handoff, the session owns a restoration checkpoint:

```text
position + play intent + track selections + subtitle state/delay/style intent
+ rate + video layout intent + source/content identity
```

The order is strict:

1. cancel generation work;
2. release old engine/provider/surface and receive affirmative completion;
3. resolve/reselect only when policy requires it;
4. attach the new graph surface;
5. start paused at the checkpoint;
6. restore effective tracks/settings after the new catalog is known;
7. seek/confirm position;
8. resume only if prior play intent was playing;
9. publish recovered state.

No old/new provider overlap is allowed.

## Content semantics

The same state machine and failure domains serve Live and VOD. Policy differs by content:

| Semantics | Live | VOD |
| --- | --- | --- |
| EOF | disconnect; reconnect | normal completion |
| Position | live edge | saved/seekable timeline |
| Replacement | zap, start-speed priority | source/episode switch, restoration priority |
| Buffer | fast startup | larger stability budget when allowed |
| Recovery | live reconnect/handoff | bounded rebuild/handoff with position restoration |

## Capability and control behavior

Each existing control is resolved to `UI_ONLY`, `APPLY_IN_PLACE`, `REBUILD_CURRENT_GRAPH`,
`RESELECT_GRAPH`, or `EXTERNAL_ACTION`. The UI displays requested/effective values and reason codes;
it never silently claims an unsupported action succeeded.

Examples:

- subtitle style may apply in place or require MPV render graph reselection;
- engine request is a preference, not a direct engine toggle;
- ordinary aspect layout remains UI/surface-host behavior;
- unsupported subtitle autosync is feature-gated by an explicit capability;
- Media3 audio/subtitle delay cannot turn a normal preference into `AUDIO_OUTPUT_FAILED`; resolver
  availability must block, adapt or select another eligible graph first.

## Lifecycle

Destination lifecycle is connected intentionally to the VOD host. Terminal destroy releases the
session and cancels every pending watchdog/retry/resolve operation. Configuration change and
retained-navigation behavior must be explicit host policies, not accidental ViewModel retention.

The current legacy `ON_DESTROY` release is temporary containment only and is removed after the
clean VOD host owns the destination.

## Atomic cutover rule

Build the clean VOD path beside the legacy controller using fakes and contract tests. Do not run
both playback paths. Activate the clean route only after every required control has a clean command
and state representation. In the same cutover, make direct engine construction and VOD recovery in
`PlayerRuntimeController` unreachable. Delete it after proof, not before.

## Decisions resolved

- One shared `PlaybackSession`; no `VodLegacyPipeline` and no separate recovery architecture.
- Existing `PlayerScreen` remains initially; playback ownership does not.
- Content-neutral internal host plus Live/VOD wrappers.
- Stable track IDs, not UI indexes.
- One absolute `SeekTo` core primitive; presentation owns preview/relative math.
- Feature coordinators own catalogs/side effects; session remains playback-focused.
- External subtitle and external-player transport use private opaque/export ports.
- No new legacy recovery logic beyond the already-required lifecycle leak containment.

## Decisions requiring implementation proof, not product choice

- Exact Media3/mpv sequencing for seek-before-first-frame and track restoration.
- Which subtitle style/autosync capabilities each adapter can truthfully expose.
- Configuration-change host retention mechanics without dual surfaces.
- Appropriate VOD watchdog budgets by stream/device evidence.

These are adapter/contract experiments with explicit tests; none changes the approved ownership
boundary.
