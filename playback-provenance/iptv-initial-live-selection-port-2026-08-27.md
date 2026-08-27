# IPTV initial live selection port — 2026-08-27

## Scope

This increment adds the provider-neutral initial-channel boundary used by the TV clean live
playback owner. It does not cut over a route or player UI and does not resolve, probe, or expose a
media URL.

## Grounded production path

1. `LiveChannelSelectionPort` accepts only an opaque `ProviderSelectionId` and a bound
   `PlaybackProfileId`.
2. `IptvLiveChannelBridge` parses a positive numeric profile and checks the active profile before
   touching provider storage.
3. `IptvIngressSelectionFactory.createForProfile` validates the stable Xtream identity and reads
   accounts for that exact profile. It produces only a `ProviderPlaybackSelection`.
4. `IptvInitialLivePresentationReader` reads sanitized display material for the exact content id
   without returning playback transport.
5. The bridge checks the active profile again, requires `ContentType.LIVE`, and requires the
   selection content key to equal the requested key before constructing `LiveChannelTarget`.
6. Provider and storage details collapse to `UNAVAILABLE`, `PROFILE_CHANGED`, or `INVALID_TARGET`.
   Structured cancellation is always rethrown.

The resulting target contains stable selection, sanitized title/logo, and a profile-scoped media
fingerprint. `playlistVersion` is intentionally unknown because the presentation reader can fall
back from the current playlist to the verified registry or the exact profile's persisted identity.

## Connection invariant

Initial selection is identity-only. `IptvLiveChannelBridge` contains no IPTV client, URL resolver,
transport probe, or engine call. The provider connection remains unopened until the playback
session resolver runs behind the release barrier.

## Verification evidence

Focused tests cover:

- exact initial selection and sanitized presentation mapping;
- active-profile rejection before reads and after a switch during reads;
- positive profile parsing and exact live content-key validation;
- coarse unavailable/invalid failures;
- structured cancellation preservation; and
- redacted request/result string forms.

Static checks on this increment: `git diff --check`, symbol/reference inspection, and a forbidden
transport/client term scan of the bridge. Gradle was deliberately not run in this parallel lane;
the root integration lane owns serialized compilation and tests.

## Platform applicability

This is a TV clean-player composition port under `NuvioTV`. Searches found no corresponding
clean-player port or bridge in NuvioMobile, NuvioDesktop, nuvio-backend, or nuvio-web, so there is
no parallel code path to port in this increment.
