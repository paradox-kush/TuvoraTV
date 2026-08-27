# Profile-bound URL-free live playlist — 2026-08-27

## Problem

`XtreamLivePlaylist` retained `LiveChannelRef` rows. Those rows include playback transport, and the
process-global snapshot had no profile identity. A profile switch could therefore leave a later
lookup observing the previous profile's channel order and browse-time provider URLs.

## Grounded boundary

- A published playlist is one immutable snapshot containing a monotonically increasing version,
  one positive numeric `PlaybackProfileId`, and `XtreamLiveChannelIdentity` rows.
- `XtreamLiveChannelIdentity` carries only stable content identity and display material. It has no
  stream URL, headers, cookies, credentials, or provider transport field.
- Publishing requires an explicit positive numeric profile. No unbound initial snapshot exists.
- Current, relative-presentation, and relative-identity lookups all require the requesting profile
  and return `null` unless it exactly matches the snapshot profile.
- Replacing the snapshot for another profile immediately makes every prior-profile lookup fail
  closed.

The Live guide and Sports are the only playlist publishers. Both capture the current
`ProfileManager.activeProfileId` at publication and project their channel rows to the new
identity-only type. Legacy live consumers remain present and resolve their transport from the
existing registry after the profile-bound identity lookup; no playback destination was cut over.

`IptvIngressSelectionFactory` now passes the explicit numeric profile through its relative source
before selecting the neighboring stable identity. `IptvInitialLivePresentationReader` likewise
passes its already-bound profile to the exact-current-playlist display lookup.

## Verification

- `XtreamLivePlaylistTest` covers ring navigation, immutable versioned presentation, absence of
  transport fields, exact profile matching, profile replacement, and rejection of zero, negative,
  and nonnumeric profile ids.
- `IptvIngressSelectionFactoryTest` proves relative presentation and account lookup receive the
  same explicit profile and that invalid profiles fail before either source is consulted.
- Initial-presentation and clean bridge fakes now carry the explicit playlist profile.
- Static call-site scan found exactly the two intended publishers and no remaining unprofiled
  playlist lookup.
- `git diff --check` passed.

Gradle was intentionally not run in this bounded parallel change; serialized build and device
verification remain with the parent integration task.

## Platform applicability

This process-local Android TV playlist and its clean IPTV ingress exist only in NuvioTV.
NuvioMobile, NuvioDesktop, `nuvio-backend`, and `nuvio-web` have no corresponding class or snapshot
to port.
