# Clean live navigation destination — 2026-08-27

Scope: NuvioTV only. This checkpoint registers the clean fullscreen destination beside the frozen
legacy player. It does not switch a production live caller.

## Invariants

- The route has one required argument: a 64-character process-local capability token.
- Route construction accepts `CleanLiveLaunchToken`; there is no raw `String` overload.
- URL, headers, provider credentials, content identity, profile identity, and display metadata never
  cross the navigation boundary.
- The ViewModel is scoped to the exact clean-player `NavBackStackEntry`.
- Surface attachment is enqueued on the ViewModel-owned scope. Compose cancellation cannot turn a
  configuration change into a terminal tune cancellation.
- Back, UI exit, and rejection share one synchronous single-flight gate.
- Navigation pops only after `releaseBeforeExit()` completes and only while the same back-stack
  entry remains current.
- `RELEASE_FAILED` stays on the destination and makes the release barrier retryable.
- Missing/expired/restored process-local tokens fail closed; no selection is reconstructed or
  persisted.

## Verification

- `:app:compileFullDebugKotlin`
- `CleanLivePlayerNavigationTest`
- `CleanLivePlayerArchitectureTest`
- `CleanLivePlayerViewModelTest`
- `ArchitectureTest`

The serialized focused gate passed on 2026-08-27. Mobile, Desktop, backend, and web have no twin for
this Android TV navigation, ViewModel, Surface, lifecycle, or MediaSession destination.

## Deferred with intent

Zap callbacks remain unreachable no-ops at this isolated registration checkpoint. They must be
wired to the versioned relative identity resolver before any production live ingress is switched.
Fire TV hardware certification is deferred because the device is off; ONN is the only device target
for this run.
