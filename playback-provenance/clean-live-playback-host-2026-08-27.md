# Clean live playback host — 2026-08-27

## Boundary

`CleanLivePlaybackHost` is the isolated TV owner for one future clean guide/fullscreen playback
instance. It is not wired to production navigation. The host creates one child `SupervisorJob`,
uses the UI-provided lifecycle/output controller and `CleanLiveSurfaceCoordinator` facts to build a
`ProductionPlaybackHost`, and exposes only immutable `PlaybackSnapshot` and
`LivePlaybackUiState` flows plus engine-neutral live commands.

The host accepts only `ProviderPlaybackSelection`; it has no concrete-request, URL, provider-link,
Media3-engine, libmpv-engine, or legacy-player entry. Media-session metadata is accepted only as
`CleanMediaSessionMetadata`, whose private constructor requires the sanitized `fromIngress` path.
Tune and zap update that display-only metadata without allowing an optional platform metadata
failure to interrupt playback.

## Construction and release authority

Construction order is coordinator, production controller, one-time controller bind, hosting start,
then best-effort MediaSession creation on its application looper. A successfully created
`CleanMediaSessionOwner` is the sole release authority. The clean controller is the fallback release
authority only if MediaSession creation fails.

Release is serialized, concurrent-safe, and idempotent. The authority first awaits the clean
session's affirmative engine/provider barrier. The coordinator may dispose and remove its raw child
only afterward, and only then is the host scope cancelled. A still-attached surface lease leaves the
host owned and retryable instead of pretending teardown completed.

## Platform scope

The owner composes Android MediaSession, Android TV surface hosting, and NuvioTV's clean production
factory. NuvioMobile, NuvioDesktop, `nuvio-backend`, and `nuvio-web` have no equivalent TV host,
surface coordinator, or clean session factory and are unaffected.
