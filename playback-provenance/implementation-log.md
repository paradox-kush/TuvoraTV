# Clean-slate playback implementation log

## Current Work Package

WP6/WP7 — deferred provider integration, phase recovery, settings, and sequential device proof.

## Changes made

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
