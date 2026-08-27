# Clean live player destination owner — 2026-08-27

## Scope

This TV-only slice adds the lifecycle-safe `CleanLivePlayerViewModel` destination owner. It does not
add a Compose screen or navigation route, switch Search/Library, resolve a URL, or reference the
legacy player.

## Initialization and identity

- Initialization accepts only an opaque route token plus the destination Activity, Lifecycle, and
  an empty `FrameLayout` surface owner.
- The committed `CleanLiveLaunchStore` consumes the token once against the current profile.
- The active profile is captured for preference composition, then read again after host creation and
  immediately before tune. A profile change releases the new host through its affirmative barrier
  before returning `PROFILE_MISMATCH`.
- A configuration-created replacement `FrameLayout` never reuses a dead surface owner. The
  destination retains only the already-consumed URL-free launch entry, releases the old host first,
  creates the replacement host, repeats the profile check, and retunes without consuming another
  route token.
- Only the URL-free `ProviderPlaybackSelection` enters `tune`, always with `FULLSCREEN` profile.
- Launch labels were sanitized before storage. The destination converts them to
  `CleanMediaSessionMetadata` using only the launch store's pre-redacted SHA-256 fingerprint.

## Android host facts

The production seam constructs `CleanLiveSurfaceCoordinator` on Main and advertises only the four
Android view/output modes that this destination can construct:

- Media3 `SURFACE_VIEW` and `TEXTURE_VIEW`;
- libmpv `NATIVE_EMBED` and `GPU_RENDER`.

Secure Media3 SurfaceView construction is enabled. Secure native/GPU output remains false in the
coordinator and production host. Decoder, DRM, device capability, and graph eligibility remain
separate clean-core decisions; no device-model check lives in the destination.

The owner supplies `AndroidPlaybackLifecyclePort`, `AndroidPlaybackOutputController`, and the final
`ProductionPlaybackSessionFactory` to `CleanLivePlaybackHost`; it imports no engine or backend.

## Release authority

The ViewModel owns a standalone `SupervisorJob`, never `viewModelScope`. Initialization, commands,
and release share one ownership mutex. `releaseBeforeExit` is non-cancellable, waits for the host's
provider/engine release barrier, and only then cancels the destination scope. `onCleared` launches
the same idempotent release path on that still-live scope. Transient teardown failures are handled
by one owner-scoped loop whose delay doubles from 100 ms and is capped at 5 seconds; it cannot stack
or bypass the provider barrier. A failure leaves the scope and host retained and exposes only typed
`RELEASE_FAILED` state until a retry proves release.

Profile-race and tune-failure cleanup also wait for host release before publishing their rejection.
No exception text crosses route state.

## Presentation

The immutable route state contains only:

- `Initializing`;
- `Ready` with sanitized metadata, explicit Search/Library origin, secret-free host snapshot, and
  engine-neutral live presentation;
- `Rejected` with a stable enum reason.

Presentation is derived atomically from each `PlaybackSnapshot`, so route state never combines a
new snapshot with a stale presentation from a second flow.

Its string forms contain no route token, provider identity, profile id, fingerprint, label, URL,
header, or exception text.

## Platform disposition

Mobile and Desktop do not contain the Android-TV clean host/composition/lifecycle/surface contracts.
Backend and web do not own local playback destinations. This owner is genuinely TV-only; no schema,
RPC, or dashboard counterpart is needed.

## Verification status

Focused tests use launch/profile/host seams rather than real playback. They cover idempotent one-shot
initialization, empty-surface validation, initial and raced profile rejection, tune/release ordering,
barrier-safe explicit exit and `onCleared`, transient release retry, replacement-surface release and
retune ordering, host fact projection, engine-neutral commands, and secret-safe state. The serialized
Gradle gate will run when released by the coordinating agent.
