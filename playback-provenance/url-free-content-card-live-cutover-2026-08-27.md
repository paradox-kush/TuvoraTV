# URL-free content-card Live TV cutover preparation — 2026-08-27

## Status

Prepared and intentionally uncommitted. Search, Library, Folder Detail, and Catalog See All now
share the clean ingress dispatch in the working tree, but this patch must remain grouped with the
future Guide and Sports atomic production cutover.

## Changed ingress behavior

For an Xtream live content identity, each of the four content-card callbacks now:

1. cancels any older in-flight ingress selection;
2. calls `CleanLiveIngressViewModel` with the stable content identity and exact typed origin;
3. navigates only when it receives `CleanLiveIngressResult.Ready`;
4. passes only the returned `CleanLiveLaunchToken` to `Screen.CleanLivePlayer`; and
5. silently contains a coarse rejection without logging provider identity or transport data.

The launch coroutine belongs to the originating `NavBackStackEntry`. Before navigation it also
requires that entry to remain the current resumed destination. Disposal of `NuvioNavHost` cancels
the remaining ingress job.

## Removed legacy behavior

These four paths no longer obtain `XtreamLiveResolverViewModel`, synchronously expose a resolved
stream URL to navigation, route true Live TV through `Screen.Player`, or call `recordPlayed` before
rendered playback success. Played-history authority remains behind the clean playback success path.

## Preserved behavior and exclusions

- Non-live Search and Folder Detail callbacks still consume their hero backdrop and open Detail.
- Non-live Library and Catalog See All callbacks still open Detail without consuming a hero
  backdrop.
- Library cloud playback is unchanged and continues through the legacy finite-media player.
- Sports, Guide, playlist playback, catch-up, VOD, and Discover are untouched.

## Static coverage

`CleanLiveIngressCutoverArchitectureTest` locks the single-dispatch lifecycle fence, exact origin
mapping, token-only clean navigation, removal of the legacy resolver/history write, and the four
non-live fallbacks. `CleanLivePlayerNavigationTest` now expects exactly one token-only clean-route
construction site.

Per task constraint, no Gradle task, device command, commit, or production certification was run.
