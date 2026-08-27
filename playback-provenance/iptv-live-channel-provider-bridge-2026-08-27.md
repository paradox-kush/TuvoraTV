# IPTV live-channel provider/storage bridge — 2026-08-27

## Scope

This TV-only bridge connects the clean `playback.live` navigation and played-history ports to the
existing IPTV playlist/account storage without introducing another playback or network owner.

## Grounded invariants

- `IptvLiveChannelBridge` is bound as both `LiveChannelNavigationPort` and
  `LivePlayedHistoryPort`; it translates only stable provider identity and sanitized presentation.
- A bound `PlaybackProfileId` must parse to a positive persisted profile id. Relative lookup checks
  the active profile both before and after the suspended provider-storage read.
- The ingress factory reads accounts through `XtreamAccountStore.accountsForProfile(profileId)`.
  It never collects the mutable active-profile account flow for clean relative lookup.
- Relative selection and presentation are returned from one factory result, and their content keys
  must be equal before a target can cross the clean port.
- Played history writes through
  `XtreamLiveStore.recordPlayedIdentityForProfile(profileId, ...)`; a profile switch cannot redirect
  a suspended write into another profile.
- History is best effort after first-frame evidence: cancellation is preserved, while ordinary
  persistence failures are contained and never fail playback.
- The bridge does not call an IPTV client, mint a URL, probe media, or open a connection. Provider
  one-connection ownership remains exclusively behind the playback session release barrier.
- Detailed store/provider failures are collapsed to the provider-neutral live-port result set.

## Focused evidence

- `IptvLiveChannelBridgeTest`: exact target mapping, pre/post profile race fences, identity
  validation, coarse failures, cancellation, and best-effort history.
- `IptvIngressSelectionFactoryTest`: explicit-profile relative lookup never reads active-profile
  accounts and preserves atomic selection/presentation identity.
- `XtreamAccountStoreProfileTest`: explicit account snapshot reads the requested profile.
- `XtreamLiveStoreTest`: explicit history write remains in the requested profile when another
  profile is active.

Static checks completed: `git diff --check`; production bridge search confirms no link resolver,
IPTV client, probe, or stream URL dependency. Gradle was intentionally left to the root agent's
single serialized verification lane.

## Platform audit

The clean Android-TV live ports and the IPTV stores/factory exist only in `NuvioTV`. Symbol searches
found no twin in `NuvioMobile`, `NuvioDesktop`, `nuvio-backend`, or `nuvio-web`, so those platforms
are genuinely unaffected by this TV-only integration.
