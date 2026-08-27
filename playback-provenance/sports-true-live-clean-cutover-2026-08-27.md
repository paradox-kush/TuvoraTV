# Sports true-live clean cutover — 2026-08-27

## Removed pre-play ownership

Sports previously resolved provider transport in `SportsHubViewModel`, opened the resulting URL,
read a byte as a health probe, registered that resolved transport, and then navigated directly to
the legacy player. This was especially unsafe for Stalker because resolving and probing can consume
a single-use link before playback owns it.

True-live Sports clicks now perform no provider resolution, HTTP request, byte read, engine choice,
or direct player navigation.

## New live flow

1. `SportsHubViewModel.playMatch` verifies the pressed match belongs to the current sheet.
2. It publishes the sheet's deduplicated channel order to `XtreamLivePlaylist` as profile-bound,
   URL-free `XtreamLiveChannelIdentity` rows.
3. It closes the match sheet.
4. It emits only the selected stable content id.
5. The Sports navigation branch calls the shared lifecycle-fenced `dispatchLiveOrElse` with
   `CleanLiveLaunchOrigin.SPORTS`.
6. A successful ingress result navigates with only the opaque clean-live launch token.

Provider media resolution therefore occurs only under the clean playback session's resolver and
release barrier. Live retry/failover can operate on fresh transport instead of a URL already opened
by the Sports UI.

Catch-up replay and provider recording flows are unchanged and continue to use their existing
finite-media routes.

## Removed code

- Sports health-probe state, byte-probe implementation, timeout/cap constants, and Offline/Checking
  row presentation;
- `RadarChannelMatcher.playbackUrlFor` and `ensurePlayable`, after a repository-wide caller scan;
- the obsolete health-probe rules test and transport-resolution matcher tests.

## Verification

- `SportsCleanLiveCutoverArchitectureTest` locks URL-free callback shape, playlist-before-close-
  before-dispatch ordering, exact SPORTS origin, shared clean-token navigation, and the absence of
  pre-play transport/probe code.
- Existing matcher tests still cover Xtream, M3U, and Stalker discovery; replay tests still cover
  catch-up dialect/session behavior.
- Repository-wide symbol and callback scans found no remaining Sports pre-play URL or byte probe.
- `git diff --check` passed.

Gradle was intentionally not run in this bounded parallel change; serialized build and device
verification remain with the parent integration task.

## Platform applicability

NuvioMobile and NuvioDesktop have their own shared Sports implementation, but they do not contain
NuvioTV's Android clean-live ingress, launch-token route, or `XtreamLivePlaylist`; this explicitly
TV-only cutover cannot be copied to them. `nuvio-backend` and `nuvio-web` have no client playback
route involved in this change.
