# Clean-slate playback implementation log

## Current Work Package

WP6/WP7 — deferred provider integration, phase recovery, settings, and sequential device proof.

## Changes made

### ONN playlist/channel replacement incident

- Stopped v1.5.9 before tagging after ONN reproduced a frozen guide frame with
  `GUIDE_RESOURCE_RESTRICTION` during playlist/channel replacement.
- Corrected the release barrier to address the adapter's still-active graph generation rather than
  the replacement request's new generation. This applies equally to strict Media3 and libmpv
  generation ownership.
- Made terminal session release wait for the result of its own release command, and fenced slow
  guide catalog/lineup commits by account generation.
- Added focused regression coverage and the complete evidence/remaining-certification record in
  `onn-playlist-switch-release-generation-rca-2026-08-27.md`.

### Compatibility outcome recording

- Added an optional session-owned recording environment and hashed scope seam. Scope keys are
  created by application/provider wiring; `PlaybackSession` never hashes or persists raw request,
  account, provider, or content secrets.
- Records one success per generation and exact graph only after a rendered video frame, or after
  `TracksAvailable` proves audio-only and `FirstAudio` has rendered. Decoder stable identity is
  included only when the adapter reported it for the current attempt.
- Records a later deterministic manifest/demux/decoder/render fatal for the exact graph so the
  persistent store can replace prior success. One closed allowlist is shared by session and store;
  network, DNS, authorization/provider-limit, TLS, DRM, resource, audio-route, and unknown outcomes
  remain inert. The libmpv `SYSTEM_DNS_FALLBACK` preflight reason is not a playback compatibility
  outcome and never enters this recorder.
- Stale generations, released lifecycle state, missing hashed scope, missing recording environment,
  and unknown engine versions are no-ops. Storage exceptions emit only a secret-safe diagnostic and
  never change playback state or recovery.

### WP4

- Extended the pinned Media3 fork at `891107e9f20eadef302920409830419659edb9b8` with
  result-bearing player release and video-output detach APIs. They preserve the acknowledgements
  already produced internally; compatibility defaults fail closed rather than manufacturing proof.
- Retained the original internal release condition after a timeout and exposed a distinct await
  operation, so hard abort can cancel this player's network calls and await real teardown without
  invoking the public release facade twice.
- Rebuilt all six consumed Media3 modules and replaced only `lib-exoplayer-release.aar`; the other
  five module hashes remained byte-identical. Updated the artifact gate and source provenance.
- Added a secret-safe, sequential ONN-to-Fire adapter smoke harness. It refuses to begin while
  either debug process is active, requires nonce-correlated package-scoped release evidence, and
  verifies process absence before advancing to the next device.
- Added the clean Media3 adapter behind the engine-neutral port: evidence-selected HLS/DASH/raw-TS
  sources, adaptive guide constraints, explicit decoder/extension ordering, audio/output mapping,
  DRM, shared network policy, contextual failures, generation-bound callbacks, and main-looper
  surface/player ownership.
- Isolated stream authentication from DRM-license authentication while retaining the same configured
  and tracked OkHttp transport. Cross-origin child loads follow the request's authorization policy;
  license requests carry only DRM headers.
- Added first and rate-limited continuing byte facts for endless raw live loads plus engine-neutral
  video-frame/audio-buffer metrics for the WP6 watchdog. Adapter facts never schedule retry.
- Split audio runtime impact honestly: skip-silence is in-place; normalization/downmix require a
  rebuilt audio pipeline. Unsupported normalization/downmix/delay paths fail explicitly rather
  than silently ignoring effective user intent.
- Closed recovery-barrier gaps: failed graceful release plus failed hard abort becomes fatal
  `RESOURCE_RELEASE_FAILED` in live and VOD recovery before any fresh resolve or provider reopen.

### WP3

- Kept `PlaybackRequirements` as the single engine-neutral effective contract; no parallel
  effective-profile model or engine-specific profile flags were added.
- Added a strict secret-safe environment snapshot/port and pure resolver for profile, effective
  preferences, passive stream evidence, runtime capabilities, resource budget, viewport, engine
  order, surface constraints, and explicit secure-output evidence.
- Made guide preview semantic rather than fixed-resolution: adaptive streams use measured viewport
  with 1.5x headroom capped by display/user/budget evidence, while raw or single-rendition streams
  retain their original decode with no invented transcode/downscale graph.
- Disabled guide display switching/AFR/GPU escalation, preserved explicit subtitle accessibility,
  kept bitrate unlimited without measured budget evidence, and restored eligible fullscreen policy.
- Separated DRM engine eligibility from secure-output evidence: DRM restricts V1 to Media3, but it
  does not invent a secure-surface requirement.
- Added surface-aware graph admission and a typed requirements diff. Decoder, subtitle, audio,
  engine, surface, GPU, and secure-output changes reselect; display/HDR/buffer changes rebuild;
  adaptive quality/bitrate-only changes apply in place.
- Moved profile/preference impact authority into `PlaybackSession`. Each selection, reconnect, and
  runtime change captures one coherent environment snapshot; stale profile resolutions are dropped,
  no-op resolutions do not touch an engine, and rejected changes preserve current playback.
- Corrected the WP2 decoder resolver so missing size/rate evidence can never affirm a hardware
  fallback for unsafe forced-software 4K AV1.

### WP2

- Added one secret-safe navigation/provider request mapper with URL user-info extraction, explicit
  authorization precedence, strict header/cookie sanitization, redirect/TLS/DNS/DRM/provider-limit
  parity, provenance-ranked passive evidence, and RTSP/RTP/UDP/HLS/DASH/file inference without a
  network probe.
- Added an isolated versioned playback preference document, strict serializer, production
  SharedPreferences store, persisted migrate-on-read, unknown-safe decoding, per-group reset, and
  an idempotent typed migration bridge covering all 70 legacy `PlayerSettings` fields without
  mutating legacy storage.
- Added requested/effective preference resolution for every persisted field with typed authority,
  availability, reason, conflicts, and runtime impact. Resolution remains conservative when secure
  output or an HDR-compatible fallback layer is not proven.
- Added refreshable Android codec/display/audio/resource/surface facts. Size/rate support remains
  coupled evidence, decoder concurrency is conservative, audio routing is unknown without active
  engine evidence, and GPU/secure surface claims remain unproven until adapter probes exist.
- Added one exact, versioned, expiring Android quirk registry. Only Amazon `AFTKM` matches the
  verified guide TextureView override; ONN and other MediaTek/Amazon family devices do not match by
  heuristic.
- Added graph-exact, runtime-exact, expiry- and size-bounded compatibility history. Network,
  authorization, provider-limit, and TLS failures cannot establish engine incompatibility.
- Routed the closed diagnostic allowlist through the central PostHog privacy sanitizer, including
  `$geoip_disable`; no provider/request/account/channel/DRM secret can enter the sink contract.
- Enabled the AndroidX instrumentation runner and repaired four stale callback signatures in an
  existing instrumentation test so real-device probes compile.

### WP1

- Added the five approved production files under `com.nuvio.tv.playback.core`: immutable domain
  models, ports, graph policy, reducer/state machine, and the sole session actor.
- Made request-bearing values redacted by construction and kept raw provider URLs, credentials,
  engine exceptions, Android APIs, Media3, libmpv, UI, persistence, and analytics SDKs outside the
  pure core.
- Serialized UI commands, normalized engine events, lifecycle events, and async completions through
  one actor lane with generation and release-epoch guards.
- Added a release barrier that cancels and joins all generation work before adapter release. A
  timeout or explicit release failure retries fail-closed and never opens a second provider
  connection or advances a zap, recovery, reconnect, or engine handoff.
- Added phase-aware startup recovery, fresh-request live reconnect, VOD in-place recovery, graph
  handoff, rapid-zap coalescing, preview-to-fullscreen promotion, and typed terminal semantics.
- Made lifecycle inactivity connection-owning: it cancels/release-barriers active work and records
  one resumable continuation; lifecycle activation resumes at most once.
- Guarded every async completion before it may mutate actor-owned caches, and captured immutable
  preferences/profile/paused inputs before launching workers.
- Added structural graph validation so Media3/libmpv output and surface ownership cannot be mixed,
  and GPU rendering requires an actual eligible `GPU_RENDER` graph.
- Added fail-closed Konsist architecture tests for core purity, adapter API containment,
  clean/legacy isolation, Android quirk placement, and settings/UI engine independence.

### WP0

- Froze accepted legacy stabilization at `8d421ebc975c3776b81ead6b5de11b2f7a4c61bd` and tagged it
  `clean-slate-legacy-baseline-2026-08-26` before this isolated branch was created.
- Rebuilt the six Media3 fork AARs from the pinned source and compared SHA-256 values.
- Added Gradle artifact and Media3 runtime-convergence verification.
- Forced the `ass-media` transitive `media3-effect` dependency from 1.8.0 to the approved 1.11.0.
- Replaced the orphaned libmpv gitlink with the exact `mpv-android-lib:0.1.12` wrapper source.
- Pinned the moving mpv, FFmpeg, dav1d, libass, and libplacebo native source revisions.
- Made the wrapper metadata generator portable across Linux and macOS hosts.
- Rebuilt all four libmpv ABIs and packaged the wrapper release AAR from the vendored recipe.

### WP5 — clean libmpv adapter (outside production routes)

- Forked the pinned 0.1.12 wrapper with affirmative surface attach/detach and core-destroy results.
  Destroy joins the event thread only when initialization actually started it, deletes retained
  Surface/global Java references, and returns success only after `mpv_terminate_destroy` completes.
- Added the reproducible mpv-core `presented-video-frame-count` patch. The counter advances only
  after a successful VO flip for a non-dropped, non-repeat frame and is the adapter's first-frame
  and continuing rendered-frame authority.
- Added a serialized, generation-bound, facts-only `playback.mpv` adapter with typed END_FILE
  mapping, track/byte/cache/decoder facts, DIRECT and RENDER graph spelling, secret-safe plans,
  TLS verification, release-proof surface leases, and initiate-once release/hard-abort awaiting.
- Kept recovery, retry, link refresh, compatibility history, settings, and engine handoff out of
  the adapter. Production routes remain on the frozen legacy player.
- Fails closed for libmpv request guarantees it cannot exactly enforce: redirect rejection,
  application DNS, cross-host auth stripping, total call timeout, and independent custom
  connect/read timeouts. Explicit auth preservation remains eligible.
- Rebuilt all four native ABIs and switched runtime consumption from the upstream Maven artifact to
  the checked local fork AAR (`44747a57bef59979d32ab2b28d9b582cb05e91684d53f1bdf5f120183b380a8b`).
- Corrected the upstream `getPropertyLong` JNI bridge, which previously truncated INT64 properties
  through Java `Integer`; watchdog frame counters now remain truthful across their full lifetime.
- Applied the approved V1 DNS materialization rule: libmpv remains on system DNS when the logical
  request selects the app's DoH resolver. The immutable adapter plan exposes
  `SYSTEM_FALLBACK_FOR_APPLICATION_DNS`; it does not claim to have applied DoH, and any resulting
  DNS/network outcome remains in a non-learnable network domain.

## Tests run

- Unified post-deferred-selection/DNS-fallback TV gate:
  `:app:verifyMedia3RuntimeConvergence :app:verifyPlaybackEngineArtifacts
  :app:testFullDebugUnitTest :app:compileFullDebugKotlin --rerun-tasks` — passed; 41 tasks executed,
  2,126 tests run, 0 failures, 0 errors, and 3 skipped. A preceding incremental run exposed one
  stale Kotlin constructor ABI in `PlaybackRequestSafetyTest`; a forced-fresh focused rerun passed
  before the complete forced-fresh gate.
- Signed `packageFullDebug` completed after constraining the packaging worker to one thread and
  raising only that invocation's Gradle heap to 8 GiB. The initial 4 GiB packaging attempts failed
  inside Android's APK compressor with `OutOfMemoryError`; compilation, tests, signing validation,
  and playback code were not the cause. ONN reports a 32-bit primary ABI, so certification uses the
  generated `armeabi-v7a` APK with SHA-256
  `e12167f01595f73402d688259dc009c5993d137806c04b8757da07a091454e8a`.

- Focused WP5 libmpv adapter gate — 11 plan/engine tests passed; production and test Kotlin
  compilation passed against the first fork artifact. Final fork verification/compile rerun follows
  the lifecycle-proof rebuild below.
- Vendored WP5 libmpv native rebuild — mpv core rebuilt for armv7, arm64, x86, and x86_64; wrapper
  JNI/AAR `assembleDebug` and `assembleRelease` passed after the final attach/detach/destroy proof
  changes.

- Media3 fork acknowledgement tests — 12 parameterized cases passed across preload/per-stream
  combinations for normal release, retained release acknowledgement, and video-output detach.
- Six Media3 release AAR builds — passed; only the intentionally changed exoplayer archive hash
  changed.
- `:app:verifyPlaybackEngineArtifacts` — passed against the rebuilt fork artifact.
- Device smoke harness tests — 27 passed; no provider stream was opened.
- Focused WP4 adapter gate — 19 Media3 tests and 8 architecture tests passed; TV production and test
  Kotlin compilation passed.
- Full WP4 TV gate:
  `:app:verifyMedia3RuntimeConvergence :app:verifyPlaybackEngineArtifacts
  :app:testFullDebugUnitTest :app:compileFullDebugKotlin --rerun-tasks` — passed; 41 tasks executed.

- Focused WP4 prerequisite core gate — 97 passed; coverage includes evidence/network-intent
  delivery to adapters, bounded graceful-to-hard-abort release, terminal fail-closed barriers,
  one-provider-connection preservation, and video reconnect refusing audio-only progress.
- Full WP3 TV gate:
  `:app:verifyMedia3RuntimeConvergence :app:verifyPlaybackEngineArtifacts
  :app:testFullDebugUnitTest :app:compileFullDebugKotlin --rerun-tasks` — passed; 41 tasks executed.
- Focused WP3 core/settings/environment-mapper/architecture gate — 134 passed after production and
  test compilation;
  coverage includes pure guide/fullscreen resolution, raw TS behavior, DRM/secure separation,
  surface admission, typed diff classification, in-place promotion, release-barrier reselection,
  and stale profile-resolution rejection.
- Consolidated audited WP2 gate — 71 passed across settings, Android capabilities/quirks,
  request/history/diagnostics wiring, core compatibility contracts, and architecture firewalls.
- Sequential AndroidX device smoke probes — passed on ONN API 34 first, then Fire TV AFTKM API 30;
  each debug process was stopped/confirmed absent before switching devices.
- ONN facts: 19 decoder entries, 3840x2160 display, HDR10 + HLG, unknown active audio route, no
  applied quirk.
- Fire TV facts: 30 decoder entries, 1920x1080 display, unknown active audio route, and the exact
  `amazon-aftkm-embedded-surface-texture-v1` quirk positively applied.
- Full WP2 TV gate:
  `:app:verifyMedia3RuntimeConvergence :app:verifyPlaybackEngineArtifacts
  :app:testFullDebugUnitTest :app:compileFullDebugKotlin --rerun-tasks` — passed; 41 tasks executed.
- Full WP1 TV gate:
  `:app:verifyMedia3RuntimeConvergence :app:verifyPlaybackEngineArtifacts
  :app:testFullDebugUnitTest :app:compileFullDebugKotlin --rerun-tasks` — passed; 41 tasks executed.
- Consolidated WP1 gate:
  `:app:testFullDebugUnitTest --tests 'com.nuvio.tv.playback.core.*' --tests
  'com.nuvio.tv.arch.ArchitectureTest'` — passed.
- WP1 core tests — 67 passed: 9 domain contract, 14 policy, 5 request safety, 9 session
  concurrency/integration, and 30 reducer/state-machine tests.
- Architecture firewalls — 8 passed and assert non-empty production/clean scopes so the rules fail
  closed instead of passing vacuously.
- Session coverage includes explicit release failure before zap, VOD recovery, every live reconnect
  attempt, and cross-engine handoff; lifecycle inactive/resume; stale completion rejection; and the
  one-provider-connection invariant.
- `:app:testFullDebugUnitTest :app:compileFullDebugKotlin --rerun-tasks` — passed on the frozen
  baseline (39 tasks executed).
- Six Media3 release AAR builds — passed; every output hash matched the checked-in artifact.
- `:app:verifyMedia3RuntimeConvergence :app:verifyPlaybackEngineArtifacts` — passed.
- `dependencyInsight` for `media3-effect` — resolves 1.8.0 to 1.11.0 by the convergence rule.
- Vendored libmpv native build for x86, x86_64, arm64, and armv7 — passed.
- Vendored wrapper `assembleDebug` and `assembleRelease` — passed.
- Rebuilt/reference AAR path manifest — identical 56 archive paths.
- Rebuilt/reference native feature inspection — all four ABIs expose the same mpv revision,
  `mediacodec_embed`, and GPU/GPU-next render paths.

## Evidence discovered

- Media3 fork source is `paradox-kush/media:nuvio-engine/1.11.0` at `350b29df6d`.
- The shipped libmpv AAR contains `mediacodec_embed` and GPU output in every ABI.
- The published libmpv release recipe used moving native branches; the shipped binary exposes the
  exact revisions now recorded and pinned by the vendored recipe.
- The rebuilt AAR hash is `4a5b666982e77820bdb6e2a0dded42f5fab733bd0ab4a70d7f4ffdf2ee6f9561`.
  It is not byte-identical to the published AAR because the native engine embeds per-ABI compile
  times and the published NDK C++ runtime retained debug sections that the local release task strips.
  The published `bb1a…effc` artifact remains the exact consumption gate.
- The legacy `libmpv-android` path was a gitlink to an unavailable object with no remote mapping.

## Architecture impact

- WP3 resolves both guide and fullscreen without constructing an engine. UI emits only requested
  profile/preferences; the serialized session owns environment refresh, comparison, and impact.
- Compatibility/surface constraints remain typed inputs to one requirements contract. DRM and
  device history cannot silently manufacture secure-output or platform capability evidence.
- WP2 completes policy inputs without constructing Media3, libmpv, or the legacy player. Legacy
  production settings/UI behavior remains untouched; only a typed one-way cutover mapper reads it.
- Observation sequence/timestamp is separate from the stable capability/device/firmware fingerprint
  used by compatibility history, so a route or memory refresh does not erase learned evidence.
- Device checks exist only in `playback.android`; settings and UI still cannot construct or directly
  configure an engine.
- The clean core is additive only; no playback routing or legacy production implementation has
  changed yet.
- `PlaybackSession` is the only orchestration owner. Reducer and policy are pure; the Media3 adapter
  reports facts and executes commands but cannot retry, select engines, or own recovery.
- `PlaybackEngineStart` now carries resolved stream evidence and the exact secret-bearing request;
  the request has a secret-safe engine-neutral proxy/timeout/retry contract.
- Graceful release and idempotent hard abort are separate engine operations. The session makes one
  bounded attempt at each and enters a terminal failed state without advancing its continuation if
  neither affirmatively ends ownership.
- Audio progress is no longer accepted as video success: only confirmed audio-only tracks may play
  or reconnect on `FirstAudio`; video sources require `FirstVideoFrame`.
- Audit remediation extended the existing contracts and adapter ownership boundaries instead of
  adding recovery coordinators or a second settings/policy system.
- `MPV_DIRECT` is build-time feasible, but runtime and surface eligibility remain adapter/device
  gates rather than assumptions.
- The Media3 runtime now has one enforceable version instead of a latent 1.8.0/1.11.0 mixture.
- `NuvioMobile`, `NuvioDesktop`, `nuvio-backend`, and `nuvio-web` were searched for the WP4 adapter
  and engine-metrics symbols and contain no corresponding playback path; this work is genuinely
  TV-only as scoped.

## Open blockers

- Real-device WP2 runs are fact/API smoke tests, not decode, EGL, active-audio-route, secure-output,
  or surface-lifecycle proof. Those require the WP4/WP5 adapter fixture gates.
- Media3 hard abort is implemented and host-tested, but real-device renderer/surface/provider release
  proof still requires the isolated debug playback entry point and sequential device run.
- libmpv must implement the same affirmative adapter-specific hard abort in WP5.
- Runtime DIRECT/RENDER and surface lifecycle proof belongs to WP5 and mandatory device validation.

## Next action

Build the isolated debug playback entry point and smoke instrumentation, then certify Media3 on ONN
and Fire TV sequentially with separate saved IPTV profiles and the device harness.
## 2026-08-27 — simulator VOD decoder/retry loop found during Live Guide validation

- The API 36 Android TV simulator rejected a 3840x1604 10-bit HEVC VOD track as
  `NO_EXCEEDS_CAPABILITIES`; `c2.goldfish.hevc.decoder` failed with codec error `0xe`.
- Subtitle fetching was not the failure. It repeated because every player rebuild fetched subtitle
  candidates again before failing the same decoder.
- The legacy VOD handler incorrectly routed a video-renderer failure into an audio fallback ladder,
  then reset the PCM-attempt flag during initialization, making the ladder unbounded. This ladder
  will not be expanded: VOD playback ownership is approved to move under the shared clean session.
- Drawer navigation retained the VOD ViewModel after leaving the player, allowing the queued retry
  to continue behind the Sports screen.
- Full RCA: `simulator-vod-retry-loop-rca-2026-08-27.md`.
- The destination-destroy release is the only temporary legacy containment fix. The existing
  `PlayerScreen` UX remains, while decoder/retry/engine/source/surface ownership moves completely to
  `PlaybackSession` through a thin presentation bridge.
- Release remains stopped pending clean VOD cutover planning, implementation, and simulator
  revalidation.
- Grounded the approved boundary in three implementation inputs:
  `vod-clean-session-responsibility-map-2026-08-27.md`,
  `vod-clean-session-design-plan-2026-08-27.md`, and
  `vod-clean-session-implementation-plan-2026-08-27.md`.
- The plans preserve the existing PlayerScreen experience while making the shared PlaybackSession
  the sole VOD engine/decoder/recovery/source/surface/lifecycle authority. The production VOD route
  stays disabled until the missing controls and feature boundaries are implemented and tested below
  the UI.

## 2026-08-27 — clean VOD foundation implemented below the production route

- Added engine-neutral VOD start/resume position, seek, timeline/buffer/seekability facts,
  playback-rate commands/facts, stable audio/subtitle catalogs and selection commands, VOD EOF
  completion, and cross-engine restoration checkpoints.
- `PlaybackSession` remains the only control/recovery owner. It forwards generation-bound controls,
  preserves VOD state through graph rebuild/handoff, and records unsupported control failures as
  non-terminal facts instead of tearing down healthy playback.
- Media3 and libmpv adapters now expose the same seek/rate/track/timeline command/fact surface.
- Added a destination-scoped external-subtitle registry. Presentation and snapshots carry only an
  opaque ID; transport is private, redacted in string output, and cleared after the host release
  barrier. libmpv uses `sub-add`; Media3 rebuilds its `MediaItem` once at the current position while
  preserving play intent, because side-loaded subtitle configuration belongs to the media item.
- Added a VOD host wrapper around the content-neutral clean host internals and an engine-free
  presentation bridge mapping the existing UI's index-based choices to stable clean track IDs.
- Focused VOD/core/Media3/libmpv/host/bridge tests passed and TV production/test Kotlin compilation
  passed. No production VOD route was enabled, so there is no dual playback authority.
- Platform audit: Mobile/Desktop use separate KMP player runtimes and backend/web have no decoder or
  surface pipeline; the new clean VOD symbols have no cross-platform twins. This phase is TV-only.
- Still release-blocking: feature coordinators; subtitle delay/style/autosync capability semantics;
  generic PlayerScreen output binding; the atomic route cutover and legacy VOD owner removal; full
  simulator VOD matrix and later ONN/Fire TV certification. VOD is not yet declared migrated.

## 2026-08-27 — VOD current-route regression pass and two startup fixes

- Installed the packaged arm64 debug build on the API 36 Android TV simulator and replayed the IPTV
  profile's progressive H.264 MP4 with the ONN device/player processes stopped.
- Confirmed real video output at 1920x800 and preserved the existing full-screen VOD controls.
- Removed the MediaSession metadata-only placeholder `MediaItem`. It had no URI, caused a caught
  `DefaultMediaSourceFactory.createMediaSource` NPE during every VOD startup, and let metadata
  presentation mutate playback ownership. Metadata now rides the real source; post-fix startup has
  no metadata NPE.
- Aligned player resume eligibility with Continue Watching. Any positive non-complete checkpoint is
  now restored, including entries below 2%; final trace restored `439738ms` and advanced from
  `439s`.
- Split unsupported Media3 subtitle-delay processing from the audio domain as
  `SUBTITLE_OUTPUT_UNSUPPORTED/SUBTITLE`; focused taxonomy coverage proves it cannot enter an audio
  fallback path.
- Focused VOD resume, Media3 taxonomy, architecture, and compile checks passed. APK packaging passed
  with one worker and a temporary 6 GiB heap after the default 4 GiB package invocation exhausted
  Zipflinger heap; code compilation and tests were not the cause.
- Complete `:app:testFullDebugUnitTest :app:compileFullDebugKotlin` gate passed after the fixes
  (39 tasks, build successful). `git diff --check` is clean.
- Cross-platform twin audit found none of the changed TV MediaSession, resume-eligibility, or clean
  subtitle-failure symbols in Mobile, Desktop, backend, or web. Those platforms use different
  player/runtime boundaries, so no applicable port exists for this explicitly TV-only work.
- Simulator playback was force-stopped after proof to release the one-connection provider slot.
- Release remains stopped: this validates and repairs the current route but does not replace the
  legacy VOD ownership. Atomic clean-session cutover, feature-coordinator parity, surface binding,
  and the unsupported 4K HEVC recovery matrix remain open.

## 2026-08-27 — unsupported 4K VOD loop made terminal

- Reproduced the failure on the API 36 TV simulator with a 3840x2160 HEVC Matroska VOD. Media3
  reported `NO_EXCEEDS_CAPABILITIES`; `c2.goldfish.hevc.decoder` failed with codec error `0xe`.
- Deleted the legacy generic decoding-error branch that routed a video-renderer failure through
  audio fallback. Explicit audio-track failures retain their bounded audio fallback behavior.
- Added deterministic video capability classification for renderer formats that are unsupported
  or exceed device capability and excluded those failures from same-graph retry.
- Added unit coverage proving a video renderer `FORMAT_EXCEEDS_CAPABILITIES` failure is terminal.
- Simulator validation after the correction recorded one subtitle fetch, one player
  initialization, and one decoder failure followed by a stable playback-error screen; there was no
  repeated `Starting stream` / `Fetching subtitles` cycle.
- This is a narrow deletion of invalid cross-domain recovery, not a new legacy recovery ladder.
  Release remains stopped pending the approved clean VOD session cutover and its certification.

## 2026-08-28 — post-cutover repair round (emulator-exposed)

- Legacy import materialized-default bug: `LegacyPlayerSettingsSnapshotMapper` mapped the
  materialized `PlayerSettings` object, so profiles that never stored `internal_player_engine` /
  `auto_switch_internal_player_on_error` imported the data-class defaults (`EXOPLAYER`, `false`)
  as explicit clean preferences — pinning every untouched profile to Media3 with automatic
  fallback disabled, reverting the live libmpv default and disabling engine handoff. Fixed via
  stored-raw-key gating (`PlayerSettingsDataStore.storedPlayerSettingKeyNames` → snapshot
  `storedFieldNames`); only genuinely stored keys import for those two fields.
- Stream User-Agent parity: both engine plans defaulted to new `TuvoraTV/1` idents; UA-gated
  panels answered 407 on tiers that accept the legacy browser UA. Both plans now consume
  `DEFAULT_STREAM_USER_AGENT` in `playback/core`, byte-identical to the legacy stream UA.
- MPV_DIRECT subtitle veto disagreement: the plan rejected direct render whenever subtitles were
  enabled at all, while selection excludes it only for FULL fidelity — with default preferences
  every guide live tune selected MPV_DIRECT, was vetoed at engine start
  (NO_ELIGIBLE_GRAPH/DEVICE_RESOURCE), and silently landed on Media3. **Product decision
  (user, 2026-08-28): video is priority; subtitles are best-effort.** The plan now mirrors the
  selection filter (only SOFTWARE decode or FULL-fidelity subtitles exclude direct) and sets
  `sid=no` on direct graphs since mediacodec_embed cannot draw them.
- Emulator caveat: goldfish decoders remain broken for these streams (androidx/media#2461), so
  guide preview (direct/hwdec) may still fail on AVDs; fullscreen (render-first + mpv software
  fallback) is the emulator-playable path. Device certification remains the ONN/Fire matrix.

## 2026-08-28 — release-wedge RCA chain and device certification (emulator + ONN)

- Thread-dump-anchored RCA of "stuck on releasing": `MPV.command("stop")` blocked in native
  (`AndroidMpvBackend.stopSource`) — a wedged mpv playloop never services synchronous commands,
  and a structured `withContext` child cannot be abandoned by any caller timeout, so the release
  barrier hung indefinitely. Fixes: `stopSource`/`detachSurface` now run as non-child backend
  tasks with bounded, abandonable awaits (`NATIVE_CALL_ABANDON_TIMEOUT_MS`); forced termination
  moved to an independent lane (a wedged serialized lane starved it); hard abort bounds its wait
  on an in-flight graceful task; teardown order now stops the VO (`vo=null`) before surface
  detach (upstream mpv-android order; detaching under a live VO is the documented native race);
  and `idle=yes` is set in the plan and re-asserted post-init (1.5.8-proven — a non-idle core
  self-terminates after `stop`, wedging every later native call).
- Root cause of the emulator wedges beneath all of it: goldfish C2 hardware decoder teardown
  deadlocks on repeated sessions (androidx/media#2461 class). Added
  `emulator-ranchu-software-video-decode-v1` quirk (manufacturer Google + ro.hardware ranchu,
  any AVD model) forcing SOFTWARE_ONLY decode through a new `ForceSoftwareVideoDecode` override
  and a hard-constraint `quirkForcedDecoder` seam in the preference resolver. Emulators are
  development surfaces; expiry set far out deliberately. Real devices are unaffected (verified:
  ONN identity does not match).
- Emulator certification (API 36 arm64 AVD): guide tune + 5-zap matrix all barriers completed,
  zero failures/watchdogs; guide preview renders via Media3+software; fullscreen renders via
  LIBMPV (gpu render, software decode); rapid fullscreen zap = no wedges (known conflation gap:
  direction-only twin lands short — fullscreen zap path still pending the settled exact-id fix).
- ONN certification (real Amlogic hardware, v7a debug build over existing data): first live tune
  selects LIBMPV via the corrected import (AUTO); 5-zap matrix across 4K channels — every
  release barrier completed, zero failures, seamless LIBMPV→MEDIA3 handoffs on channels mpv
  could not render; 4K UHD confirmed rendering in guide preview and fullscreen; 100s fullscreen
  soak stable; zero crashes in the crash buffer.
- The `isDebuggable=true` diagnostic flip used for the jdb thread dump has been reverted; the
  builds installed on the emulator and ONN during this session carry it and must not be released.

## 2026-08-28 — fullscreen settled zap (closes the review's remaining zap facet)

- The fullscreen player's zap twin still used a CONFLATED queue of relative directions: rapid
  presses dropped intermediate steps (4 presses landed 2 channels away) and each drained command
  paid a full release barrier + fresh provider link. Replaced with the guide's settled design:
  presses accumulate as signed steps, one shared settle window (`LiveZapSettlePolicy.SETTLE_MS`,
  the 1.5.8-proven 450ms, now centralized and consumed by guide and fullscreen), then ONE
  committed tune resolved by walking the provider-neutral ring exactly N steps from the playing
  channel. Opposing presses net out; a net-zero hold commits nothing and opens no connection.
- Settle wait is injectable (`CleanLiveZapSettleWait`) mirroring the release-retry pattern so the
  ViewModel tests drive the window deterministically; tests cover exact +10 landing on one tune,
  net-out, and net-zero. Emulator verification: six sub-settle presses produced exactly one
  release barrier + one resolution + one LIBMPV tune; presses spaced beyond the window commit
  individually as intended. TV-only (no CleanLivePlayerViewModel/LiveZapSettlePolicy twins in
  Mobile/Desktop).

## 2026-08-29 — review backlog sweep (correctness, efficiency, dedup)

Three commits close the max-effort review's confirmed backlog: the remaining correctness
findings (preferences-change loss during selection, reconnect pause, retry generation
acknowledgement, idempotent session release + closed-lane dispatch, typed Media3 apply failure,
loud quirk staleness, guide attach-input leak, pre-attach mpv event subscription, hardened VOD
bridge), the hot-path efficiency items (cached compatibility history with write-on-change only,
memoized codec walk, tick-stable UI snapshots + gated MediaSession invalidation, completion-driven
awaitIdle, callback-driven SurfaceView validity waits), and the mechanical dedups (one
assembledHttpHeaders, one TrackRestorationPolicy scorer, one ContentFrameRatePolicy band that
replaced FOUR copies, Media3Engine finish(hard) factoring, dead host presentation flow and the
test-only ResolveRequest accessor removed).

Cross-platform audit (root parity rule): the review flagged the TV VOD-retry fix as unported to
NuvioMobile. Verified: mobile's decoder-failure handler (PlayerEngine.android.kt isDecoderFailure)
responds to capability-exceeded failures with a ONE-SHOT switch to extension renderers — a
different decoder graph that can legitimately succeed in software. That is detect-then-switch,
not the TV bug (unbounded same-graph retry), so no mobile change is required; this entry is the
previously missing audit statement. Desktop mirrors mobile's engine and needs nothing either.

Deliberately deferred to a dedicated refactor pass (structural moves on freshly certified seams;
each needs its own device re-verification): the guide/fullscreen ViewModel host-machinery
extraction, the Media3/mpv engine skeleton and backend-event unification, the Zap/Tune command
merge, the host-factory builder share, and the AndroidDisplayModeSelector/FrameRateUtils AFR
consolidation (legacy player still ships its copy). Latent until their features cut over:
clean-lane catch-up winner persistence (resolver has no playback-proven hook; legacy coordinator
still owns production catch-up). Product decisions awaiting the user: sports dead-channel
pre-play signal replacement and the richer viewer-facing failure detail the legacy freeze screen
carried.

## 2026-08-29 — clean-build regression pass (emulator + ONN, post-backlog-sweep)

- Rebuilt at 6d4b2308e with the diagnostic `isDebuggable` flip reverted; aapt confirms no
  `application-debuggable` in the shipped APKs. Installed arm64 on the API 36 AVD and v7a on ONN.
- Emulator: 8 tunes (initial + 5-zap matrix + one settled rapid-guide commit + one settled rapid
  fullscreen zap) — 8/8 release barriers completed, both 6-press and 4-press rapid bursts settled
  to exactly ONE committed tune each, fullscreen rendering verified (98% non-black frame). One
  transient NO_ELIGIBLE_GRAPH at the fullscreen promote recovered on the next generation
  (LIBMPV playing); emulator/software-quirk-specific — did NOT reproduce on ONN; watch item.
- ONN: 8 tunes, 8/8 barriers, ZERO failures/watchdogs, one seamless engine handoff, both rapid
  bursts settled to one tune. During the fullscreen soak the provider dropped the stream
  (`end-file` ~76s in); the bounded reconnect recovered on attempt 0 (video-reconfig immediately
  after, no further attempts, no exhaustion) and the system media session reports PLAYING with
  no error — the new recovery ladder working unattended on real hardware. Zero crashes.
- Screenrecord captured black for this ONN fullscreen graph (hardware overlay path); playback
  state, event silence, and the reconnect telemetry are the authoritative signals per the
  existing screenshot-black guidance.
