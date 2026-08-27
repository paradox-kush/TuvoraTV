# Shared Android clean-live host facade — 2026-08-27

## Scope

The fullscreen clean-live destination previously owned an Android-specific host input, factory,
surface composition, and forwarding adapter as private UI-layer implementation details. That made
the production composition unavailable to a future clean guide surface and let Android playback
wiring leak into the fullscreen ViewModel.

This change extracts that boundary to `playback.host` without changing fullscreen behavior.

## Resulting boundary

- `CleanLiveHost` is the engine-neutral Android live command facade. It exposes the snapshot plus
  tune, zap, pause, resume, retry, profile change, stop, and release.
- Tune and zap return the exact generation accepted by `CleanLivePlaybackHost`; the facade does not
  infer acceptance from time or from a later snapshot.
- `CleanLiveHostFactory` accepts the Android owner dependencies needed for one live destination.
- `AndroidCleanLiveHostFactory` is the production composition root for the surface coordinator,
  output controller, lifecycle port, and `CleanLivePlaybackHost`.
- The fullscreen ViewModel depends only on the shared facade and factory. Media3 and libmpv APIs
  remain behind the existing playback session adapters and are not imported by the facade or UI.

The release barrier and cancellation behavior remain owned by `CleanLivePlaybackHost`; the shared
adapter delegates without adding scopes, retries, timeouts, or alternative release paths.

## Verification

- `CleanLiveHostFacadeArchitectureTest` locks the complete facade command surface, accepted-
  generation return types, Android composition ownership, and absence of engine imports.
- Existing `CleanLivePlayerViewModelTest` fakes implement the extracted facade, preserving the
  fullscreen lifecycle, release, tune, zap, and first-frame history coverage.
- Static symbol scan found no remaining private destination-host factory/adapter definitions.
- `git diff --check` passed.

Gradle was intentionally not run in this bounded parallel change; serialized build and device
verification remain with the parent integration task.

## Platform applicability

This is an Android TV/Fire TV clean-live composition boundary in NuvioTV. NuvioMobile,
NuvioDesktop, `nuvio-backend`, and `nuvio-web` do not contain this Android destination/surface host
and therefore have no corresponding implementation to port.
