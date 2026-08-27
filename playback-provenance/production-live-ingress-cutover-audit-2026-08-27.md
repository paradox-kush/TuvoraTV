# Production live ingress cutover audit — 2026-08-27

This is a secret-free, read-only map of every NuvioTV production live-TV entry point at clean-player
commit `e5e1a1b2f`. It records the remaining cutover work; it does not authorize a partial production
migration or a permanent dual-route flag.

## Global finding

Production has no call to `PlaybackSession`, `PlaybackSessionController`, `Media3Engine`, or
`MpvEngine`. The clean engines are reachable only from the debug lab. Production playback still
uses either inline guide-owned Exo/libmpv state or the URL-bearing `Screen.Player -> PlayerScreen ->
PlayerViewModel -> PlayerRuntimeController` chain.

Before any ingress can move, production needs one composition root for the clean session, engines,
surfaces, lifecycle/output ports, preference/history inputs, and strict deferred provider resolver.

## Complete live ingress map

| Ingress | Current legacy path | Required clean seam |
| --- | --- | --- |
| IPTV guide preview/fullscreen/zap | `XtreamHubScreen` -> `XtreamLiveGuideScreen/ViewModel`; the guide owns Exo/libmpv and resolves/prepares links before playback | One clean guide host and session controller; URL-free `Tune`/`Zap`; `GUIDE`/`FULLSCREEN` profile changes; lifecycle release |
| Search live | `NuvioNavHost` -> `XtreamLiveResolverViewModel.resolve` -> URL-bearing `Screen.Player` | Live-only clean destination carrying an opaque provider selection |
| Library live | `NuvioNavHost` -> live resolver -> URL-bearing `Screen.Player` | Same live-only destination; fresh link stays behind the session resolver |
| Sports live | `SportsHubViewModel` -> `RadarChannelMatcher.playbackUrlFor`/optional probe -> URL callback -> `Screen.Player` | Sports returns the selected opaque channel/content identity; no mint/probe while another provider stream is owned |
| Guide catch-up | `CatchUpPlaybackCoordinator.begin` mints before navigation -> legacy `Screen.Player` flags | Deferred `CATCH_UP` selection with bounds; resolver owns first link and transport/demux-only dialect advance |
| Sports catch-up | Sports matcher -> catch-up coordinator -> URL callback -> legacy player | Same finite deferred catch-up path; Sports retains discovery/return metadata only |
| Fullscreen live zap | `PlayerViewModel.zapLive` refreshes/prepares -> `PlayerRuntimeController.switchToLiveChannel` | `PlaybackSessionController.zap(selection, FULLSCREEN)`; session owns release and fresh resolution |

No additional production live ingress was found. Other `Screen.Player.createRoute` calls at the
audited non-live locations are VOD/general stream paths and must not be accidentally changed by a
live-only cutover.

## Critical behavior to remove, not port

- `PlaylistLivePlayback` contains legacy DNS/redirect preparation and disables certificate/hostname
  validation. The clean request contract must not inherit that global TLS behavior.
- Sports can probe a stable URL before launch, creating a second provider request. Any future health
  probe must be explicitly connection-budget-aware and cannot run while playback owns the account.
- Stalker and catch-up paths mint links before navigation. Clean resolution happens only after the
  affirmative release barrier.
- Legacy guide recovery and fullscreen IPTV refresh each independently reopen links. The clean
  session is the sole recovery owner.

## Settings and MediaSession gaps

- The clean settings repository/UI is debug-reachable only. Production needs one profile-scoped
  repository, idempotent legacy import at cutover, effective preference resolution, and
  `PreferencesChanged` dispatch. There must not be two writable authorities for the same setting.
- Legacy system media controls are Exo-bound and effectively absent for mpv. V1 requires an
  engine-neutral MediaSession facade backed by `PlaybackSnapshot` and
  `PlaybackSessionController`; it must not create a hidden ExoPlayer for libmpv.

## Safe implementation order

1. Complete provider resolver, compatibility recording, and production composition.
2. Add the isolated clean live destination and validate Search/Library.
3. Move guide preview/fullscreen/zap to one clean host while preserving EPG/focus/layout.
4. Move Sports live and remove pre-navigation link mint/probe overlap.
5. Move guide and Sports catch-up to the common finite deferred path.
6. Activate clean settings and engine-neutral MediaSession.
7. Complete pre-cutover ONN/Fire matrices.
8. Atomically switch every remaining production playback ingress and delete legacy owners.
9. Repeat all gates and device certification on the post-deletion tree.

## Collision and scope notes

- `NuvioNavHost.kt` is the shared choke point; use a new clean live destination and small callback
  substitutions rather than changing `Screen.Player` globally.
- `XtreamLiveGuideScreen.kt` and `XtreamLiveGuideViewModel.kt` remain hot legacy files. Keep clean
  orchestration in a separate host/mapper until the atomic replacement.
- Mobile/Desktop have their own KMP live/Sports implementations but no NuvioTV clean contracts.
  Backend/web have no player engine or matching schema/RPC dependency. All four were explicitly
  grepped and are genuinely unaffected by this TV-only audit.
