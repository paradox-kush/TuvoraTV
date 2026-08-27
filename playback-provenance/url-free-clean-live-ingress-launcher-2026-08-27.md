# URL-free clean Live ingress launcher — 2026-08-27

## Scope

This change adds the TV-only composition boundary needed before production Live TV callers move to
the clean player. It does not switch Search, Library, Sports, folder, catalog-see-all, or guide
navigation.

## Grounded contract

- The caller supplies only a stable live content identity and a typed launch origin.
- `CleanLiveIngressLauncher` captures the current numeric profile once, converts it to the opaque
  playback profile identity, and asks `LiveChannelSelectionPort` for one atomic target.
- The profile is checked again after selection. A profile race produces a coarse rejection and no
  launch entry.
- The exact `LiveChannelTarget` returned by the selection port is inserted into
  `CleanLiveLaunchStore`; selection, title, playlist version, and fingerprint are not rebuilt.
- Navigation receives only `CleanLiveLaunchToken`. Failures expose only invalid-request,
  unavailable, or profile-changed categories.
- Coroutine cancellation is rethrown. Ordinary selection and launch-store exceptions fail closed.
- The Hilt ViewModel is a thin navigation wrapper and owns no provider client, network probe,
  resolver URL, or playback engine.

## Origin coverage

The launch origin vocabulary now covers `SEARCH`, `LIBRARY`, `SPORTS`, `FOLDER`, and
`CATALOG_SEE_ALL`. Existing Search and Library values remain unchanged.

## Verification added

- `CleanLiveLaunchStoreTest` proves direct insertion preserves the same target object.
- `CleanLiveIngressLauncherTest` covers profile capture, the post-selection profile fence, exact
  target forwarding, coarse failure reduction, fail-closed exceptions, cancellation, and the thin
  ViewModel wrapper.

Per task constraint, no Gradle task or device command was run. Production callers remain unchanged,
so ONN certification belongs to the later atomic cutover rather than this foundation patch.
