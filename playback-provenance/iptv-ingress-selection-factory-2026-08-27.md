# IPTV ingress selection factory — 2026-08-27

## Boundary

`IptvIngressSelectionFactory` is the UI-independent entry boundary for Search, Library, Sports, and
guide callers. Production routes are unchanged in this work package.

Input is a stable `xtream:` content ID plus optional clean playback content type and catch-up epoch
bounds. The factory parses the right-anchored `XtreamItemRegistry` identity, verifies any available
registry metadata without reading its media URL, then loads the current profile's matching account
from `XtreamAccountStore`.

Output is either:

- a URL-free `ProviderPlaybackSelection` carrying opaque account/item/content IDs, source type,
  content type, catch-up bounds, a conservative one-connection limit, and provider-declared
  transport evidence where the source makes that fact reliable; or
- a closed, typed `IptvIngressSelectionFailure` whose string form contains no provider response,
  account identity, stable content ID, credentials, or URL.

The factory never accesses `XtreamResolvedItem.streamUrl`, calls an IPTV client, mints a Stalker or
catch-up link, performs DNS work, or probes media. Those operations remain in
`IptvProviderPlaybackResolver`, invoked by `PlaybackSession` only after affirmative release.

## Validation

- IDs must use a playable canonical positive live/VOD stream number. Series and episode catalog
  IDs are rejected because they are not direct clean playback selections.
- Legacy catch-up IDs ending in `r<start-minute>` remain accepted only when the suffix exactly
  matches the supplied programme start bound. Canonical live IDs with explicit bounds are also
  accepted.
- Registry account/kind/stream metadata, when present, must agree with the stable ID. Older registry
  entries with absent optional account/stream metadata do not override the stable parsed identity.
- The current profile must still contain the matching enabled account, its source must map exactly
  to Xtream/M3U/Stalker, and the requested live/movie content type must remain enabled.
- Catch-up V1 is Xtream-only. M3U/Stalker catch-up is rejected before a provider link can be minted.
- URL/file playlists map to the single M3U source type. Unknown future source strings fail closed.

## Relative live zap

The optional relative helper asks the existing ordered live playlist only for the neighboring
stable content ID and feeds that ID back through the same validation factory. It never returns or
carries the legacy `LiveChannelRef.streamUrl`.

## Tests

Focused tests cover all supported source mappings, live/VOD evidence, canonical and legacy Xtream
catch-up, malformed/noncanonical/overflow IDs, invalid bounds, account mismatch/missing/disabled,
unsupported and disabled content types, unknown sources, non-Xtream catch-up, account-store errors,
relative-zap selection, URL-free structure, and secret-safe string rendering.
