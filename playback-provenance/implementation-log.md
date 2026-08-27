# Clean-slate playback implementation log

## Current Work Package

WP0 — dependency ownership and reproducibility.

## Changes made

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

- No playback routing or legacy production implementation has changed.
- `MPV_DIRECT` is build-time feasible, but runtime and surface eligibility remain adapter/device
  gates rather than assumptions.
- The Media3 runtime now has one enforceable version instead of a latent 1.8.0/1.11.0 mixture.

## Open blockers

- Runtime DIRECT/RENDER and surface lifecycle proof belongs to WP5 and mandatory device validation.

## Next action

Begin the five-file pure core and its exhaustive state-machine/policy tests.
