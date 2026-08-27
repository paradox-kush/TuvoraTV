# Clean-slate playback implementation log

## Current Work Package

WP1 — pure playback core and single-owner session orchestration.

## Changes made

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

## Tests run

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

- The clean core is additive only; no playback routing or legacy production implementation has
  changed yet.
- `PlaybackSession` is the only orchestration owner. Reducer and policy are pure; engine adapters
  will report facts and execute commands but will not retry, select engines, or own recovery.
- Five production files remain the package budget. Audit remediation removed dead detach/reconnect
  action semantics instead of adding coordinators or policy layers.
- `MPV_DIRECT` is build-time feasible, but runtime and surface eligibility remain adapter/device
  gates rather than assumptions.
- The Media3 runtime now has one enforceable version instead of a latent 1.8.0/1.11.0 mixture.

## Open blockers

- `PlaybackEngine.release()` can prove an ordinary release completed, but it cannot yet express an
  affirmative native hard-abort. Until WP4/WP5 add that adapter contract, a permanently wedged
  release retries indefinitely and fail-closed; it never advances a continuation or consumes a
  second provider connection.
- Runtime DIRECT/RENDER and surface lifecycle proof belongs to WP5 and mandatory device validation.

## Next action

Commit WP1, then begin WP2 versioned playback preferences and WP3 Android capability/quirk
discovery in isolated parallel lanes.
