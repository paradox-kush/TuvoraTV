# Profile-bound provider resolution provenance — 2026-08-27

## Decision

One exact `PlaybackProfileId` is captured at playback-owner construction and is used both for
preference loading and for creating that session's `ProviderPlaybackResolver`. The type rejects
blank ids, compares by exact value, and redacts string output.

`ProductionPlaybackSessionFactory` asks `ProviderPlaybackResolverFactory` for a resolver once per
session. The IPTV factory closes over an explicit numeric profile id, and every later account read
uses `XtreamAccountStore.findForProfile`. Provider resolution therefore cannot follow
`ProfileManager.activeProfileId` after a profile switch or after a release barrier.

## Preserved behavior

- `IptvProviderPlaybackResolver` retains its narrow account/link/winner test seams.
- Link minting remains inside `PlaybackSession` after the release barrier.
- Live, VOD, Stalker failure mapping, M3U properties, catch-up dialect walking, and compatibility
  scoping are unchanged.
- An invalid nonnumeric IPTV persistence profile fails closed as no matching account; it cannot
  fall back to the active profile.

## Verification scope

- Core contract coverage checks nonblank, exact, redacted `PlaybackProfileId` behavior.
- Production composition coverage checks that the exact captured id reaches the resolver factory.
- IPTV factory coverage checks that resolver creation captures the exact id before resolution.
- Account-store coverage switches the active profile after persistence and proves explicit-profile
  lookup still reads only the captured profile.
- Static twin search found no equivalent clean playback factory/resolver path in NuvioMobile,
  NuvioDesktop, nuvio-backend, or nuvio-web; this is an Android-TV-only composition and storage
  boundary with no schema, RPC, payload, or web contract change.

Gradle execution is intentionally deferred to the root's single serialized clean-tree gate.
