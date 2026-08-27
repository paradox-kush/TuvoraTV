# Clean live surface coordinator — 2026-08-27

## Scope

This change adds an isolated TV-only Android surface coordinator for the future clean live guide
and fullscreen host. It does not wire a production route and does not replace a legacy player.
Current device certification remains ONN/Amlogic only. Fire TV is deferred and untested; this work
must not be interpreted as a Fire TV pass.

## Composition and ordering contract

The coordinator is deliberately constructed before the clean playback session:

1. A UI host supplies one dedicated, initially empty `FrameLayout` and an explicit set of surface
   modes that it can really construct.
2. Composition receives the coordinator's `Media3SurfaceHost`, `MpvSurfaceHost`, and truthful
   `SurfaceCapabilities` and creates the one `PlaybackSessionController`.
3. The UI host binds that controller exactly once and awaits `startHosting()`.
4. Only after `startHosting()` has synchronously dispatched `SurfaceAvailable` may the host tune or
   zap. This preserves `SurfaceAvailable -> Tune/Zap -> GraphSelected -> engine acquire/attach`.
5. Teardown first awaits the playback session's affirmative release barrier, then calls
   `disposeAfterSessionRelease()`.

Acquisition fails closed before binding/start, after disposal, for an unadvertised mode, or while
another child/lease is owned. Surface validity is bounded by a three-second default timeout.
Surface callbacks carry monotonically increasing ownership tokens; callbacks from a released or
replaced child cannot mutate the current session. Controlled replacement suppresses a false
`SurfaceUnavailable` transition.

## Exact Android backing

| Engine graph mode | Constructed child | Surface ownership |
| --- | --- | --- |
| Media3 `SURFACE_VIEW` | `SurfaceView` | Media3 lease attaches the view; secure is the exact request |
| Media3 `TEXTURE_VIEW` | `TextureView` | Media3 lease attaches the view; secure is rejected |
| libmpv `NATIVE_EMBED` | `SurfaceView` | Holder `Surface`/native window; coordinator does not release holder ownership |
| libmpv `GPU_RENDER` | `TextureView` | Coordinator wraps its `SurfaceTexture` and releases that owned `Surface` |

The coordinator never constructs `NuvioMpvSurfaceView`. It never labels the two libmpv paths as
equivalent. Secure output is advertised and accepted only for a proven Media3 `SurfaceView` path;
secure libmpv and secure `TextureView` requests fail closed.

## Release proof

The coordinator delegates its created Android view to `ViewMedia3SurfaceHost`, the Media3 adapter
leaf that owns the pinned fork's affirmative `ExoPlayer.clearVideoSurfaceWithResult()` call. The
leaf invokes an exactly-once released-view callback only after detach or terminal player-release
proof and successful lease release; that callback lets the coordinator remove its child without the
host package importing or implementing any ExoPlayer-facing lease. A failed clear leaves the lease
attached and blocks the callback. libmpv release similarly blocks while attached until detach or
core-destroy confirmation. Consequently a graph replacement cannot create a second raw surface
while an engine still owns the first.

## Verification and platform audit

Focused tests cover explicit capability claims, invalid construction, bind/start ordering, one-child
ownership, exact secure reporting, affirmative Media3 clear proof, distinct libmpv backing paths,
stale callback filtering, unexpected loss/recovery serialization, controlled replacement,
bounded validity failure, and disposal barriers.

Repository search found no twin of this Android `FrameLayout`/Media3/libmpv host in NuvioMobile,
NuvioDesktop, nuvio-backend, or nuvio-web. Those platforms are genuinely unaffected because this is
an unwired Android-TV host primitive, not a shared playback policy, account, provider, or API change.
