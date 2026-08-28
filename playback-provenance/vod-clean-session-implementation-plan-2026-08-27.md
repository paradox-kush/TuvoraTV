# VOD clean-session implementation plan — 2026-08-27

## Release rule

Release stays stopped until the atomic VOD cutover, legacy-owner deletion gates, simulator matrix
and required real-device certification pass. No tag or release workflow is authorized by this plan.

## Phase 0 — contain the current leak

- Release the retained legacy player when its destination reaches terminal `ON_DESTROY`, except
  during Activity configuration change.
- Do not add a legacy decoder/audio fallback repair.
- Unit-test lifecycle classification and source wiring.

Status: implemented as temporary containment; focused tests and TV compile passed.

## Phase 1 — VOD transport and restoration core

Status: implemented below the production route on 2026-08-27. Explicit start position,
generation-bound timeline facts, VOD EOF, playback rate, stable restoration checkpoints and
non-terminal control rejection are present. Focused core/adapter tests and TV compilation pass.

Add engine-neutral models, commands, reducer actions/events and snapshot facts for:

1. absolute seek and seekability;
2. position/duration/buffered timeline facts;
3. initial/resume position and start-position policy;
4. EOF completion distinct from Stop/Release/Failure;
5. playback rate;
6. restoration checkpoint and ordering.

Tests:

- stale-generation timeline/seek facts are ignored;
- same-graph recovery and one-time handoff preserve VOD position/play intent;
- lifecycle rebuild restores position without autoplay when paused;
- source replacement release completes before resolution/start;
- EOF produces completion; Stop/Release do not;
- recovery budgets remain session-scoped across engine rebuilds.

Gate: no production route change.

## Phase 2 — rich track and subtitle contract

Status: partially implemented. Stable audio/subtitle catalogs and selections, subtitle disable,
opaque destination-scoped external-subtitle registration, and secret-safe handoff restoration are
present. libmpv attaches registered subtitles directly; Media3 performs one position/play-intent
preserving `MediaItem` source rebuild. Subtitle render-style, delay effective-state/capability, and
autosync capability contracts remain open; the production route must not activate before them.

Taxonomy note: stock Media3's unsupported subtitle-delay requirement is now a deterministic,
handoff-eligible `SUBTITLE_OUTPUT_UNSUPPORTED/SUBTITLE` failure. It can no longer be mistaken for an
audio-output failure.

- Add stable engine-neutral track descriptors/catalog/selection.
- Add audio/subtitle select and disable commands.
- Add opaque external-subtitle registration/attachment.
- Add subtitle delay/style capability and effective-state reporting.
- Define capability-gated autosync support; do not emulate unavailable facts.

Adapter contract tests run identically against Media3 and libmpv fakes/backends:

- enumerate/select/disable;
- stable selection restoration after rebuild/handoff;
- external subtitle attach/select;
- delay/style apply-in-place vs graph-reselection result;
- no URL or credential in snapshots/diagnostics.

Gate: every current VOD subtitle/audio control has an honest clean representation.

## Phase 3 — adapter VOD command parity

Status: partially implemented. Both engines now support start checkpoint, seek, timeline facts,
track facts/selections, subtitle disable and playback rate. External subtitle transport is wired to
both adapters, with libmpv attachment unit proof. The shared full adapter contract suite,
Media3 external-subtitle fixture proof, and command-in-flight release tests remain open.

Implement the new port operations in `Media3Engine`/backend and `MpvEngine`/backend:

- seek/start checkpoint;
- timeline and track facts;
- select tracks/disable subtitles;
- external subtitle attachment;
- playback rate;
- supported subtitle/audio in-place requirements.

Normalize raw exceptions into the existing typed failure domains. Specifically prove a
`MediaCodecVideoRenderer` failure becomes `VIDEO_DECODER`/`VIDEO_RENDERER_SURFACE` and never enters
audio recovery. Adapters remain retry-free.

Gate: both engines pass one shared contract suite, including release/hard-abort while commands are
in flight.

## Phase 4 — content-neutral host and surface ownership

Status: implemented below the route. The existing clean host internals are content-neutral, a VOD
wrapper enforces `ContentType.VOD`, and one destination-scoped subtitle registry is cleared only
after the affirmative session release barrier. Generic PlayerScreen output binding remains part of
the atomic Phase 6 cutover.

- Extract generic internals from the clean live host/surface coordinator.
- Retain a Live wrapper enforcing `ContentType.LIVE`.
- Add a VOD wrapper enforcing `ContentType.VOD` and VOD metadata/lifecycle semantics.
- Add generic video-output binding used by the existing PlayerScreen layout.
- Extend the clean MediaSession facade with VOD seek/rate only after the core supports them.

Gate: exactly one host owns one session, surface and provider request; no UI engine object exists.

## Phase 5 — VOD feature coordinators and presentation bridge

Status: partially implemented. The engine-free `VodPlaybackPresentationBridge` maps playback facts
and play/pause, seek, rate, audio/subtitle selection, subtitle-off and retry intent, with fake-host
tests. Source/episode, subtitle discovery/autosync, progress/scrobble, reporting, post-play and
external-player export coordinators remain open.

Create feature coordinators for source/episode, subtitles, progress/scrobble, reporting and
post-play. Create `VodPlaybackPresentationBridge` that:

- maps existing `PlayerEvent` intent into typed UI/feature/playback commands;
- maps clean snapshots/catalogs into existing `PlayerUiState` and timeline fields;
- emits one-shot navigation/report/external-player effects;
- never imports Media3/libmpv, URLs, decoder policy or retry logic.

Parity tests cover every row in the responsibility map. Add source scans forbidding raw ExoPlayer,
mpv, TrackSelector, MediaCodec and provider client types in the bridge/ViewModel/PlayerScreen.

Gate: the existing screen can run completely against a fake clean VOD port with all required
controls enabled.

## Phase 6 — atomic production route cutover

- Change VOD movie/episode/source ingress to create one clean VOD host/session.
- Bind the existing PlayerScreen to the presentation bridge and generic output container.
- Disable/remove every reachable legacy VOD engine construction, decoder choice, watchdog, retry,
  failover, surface and provider-lifecycle path in the same cutover.
- Do not shadow-play both paths and do not pre-open a provider connection.

Integration proof:

- initial VOD, saved resume, paused resume;
- seek/scrub/skip intro;
- all audio/subtitle/style/delay controls;
- source and episode changes, including same URL and one-connection provider;
- engine preference and policy override reason;
- Media3 -> libmpv and libmpv -> Media3 restoration;
- natural EOF/post-play vs user exit;
- terminal destination release and no background retry;
- external player export ordering and sanitized reports.

## Phase 7 — deletion and architecture enforcement

Delete VOD playback responsibilities from `PlayerRuntimeController` and remove raw `exoPlayer`,
`mpvView`, URL/header and concrete controller exposure from PlayerScreen/PlayerViewModel. Delete the
temporary lifecycle containment policy after clean lifecycle binding is active.

Architecture tests fail if production VOD UI imports or calls:

- ExoPlayer/PlayerView/TrackSelector/MediaCodec;
- libmpv/NuvioMpvSurfaceView;
- decoder/engine selection or retry APIs;
- provider clients/URLs/headers;
- legacy VOD initialization/recovery functions.

Gate: one playback architecture remains reachable for Guide, fullscreen Live and VOD.

## Phase 8 — validation and release gate

Simulator matrix:

- supported H.264/HEVC VOD;
- unsupported 4K Main10 sample reaches bounded typed failure/handoff, never a loop;
- Media3 and libmpv command parity;
- repeated source/episode switches;
- destroy behind Sports with zero further decoder/subtitle activity;
- process/configuration/lifecycle transitions.

Real TV matrix when access returns:

- ONN and Fire TV;
- H.264/HEVC/Main10, SDR/HDR/Dolby Vision as hardware supports;
- long playback, seek, subtitle/audio changes and engine handoff;
- provider one-connection verification;
- decoder/surface release proof and no background playback.

Then run the complete TV unit/compile/artifact convergence gate. Release resumes only after explicit
approval.

Current-route regression evidence (not Phase 8 certification): the packaged arm64 debug APK played
the IPTV profile's 1920x800 H.264 MP4 on the API 36 TV simulator, displayed the existing controls,
and restored `439738ms`. The metadata-only placeholder item NPE and below-2% resume mismatch found
during this run were fixed and covered. The unsupported 4K HEVC clean-session matrix remains gated
on the atomic route cutover.

## Platform scope audit

This architecture/cutover is explicitly TV-only. Mobile and Desktop have separate KMP player
implementations and no matching `PlayerRuntimeController` or TV NavBackStack lifecycle path.
Backend and web own no client decoder/surface pipeline. All four product repositories must still be
searched at each implementation phase, and any newly discovered shared counterpart must be handled
or documented rather than silently skipped.

Audit result on 2026-08-27: the clean TV `PlaybackSession`, VOD host, presentation bridge and
destination subtitle registry have no twins in NuvioMobile, NuvioDesktop, backend or web. Mobile
and Desktop contain their own KMP player runtimes and a watch-progress data type named
`WatchProgressPlaybackSession`, not this orchestration architecture. Backend/web contain no client
decoder, surface, or external-subtitle adapter. No cross-platform port is applicable to this
explicitly TV-only cutover.
