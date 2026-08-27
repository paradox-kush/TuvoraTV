# URL-free live history and presentation — 2026-08-27

## History boundary

`XtreamLiveStore.recordPlayedIdentity(contentId, title, logo)` records clean live history without a
playback URL. The profile-scoped atomic upsert keeps a nonblank transport only when the exact legacy
row already contains one; a new identity-only row stores the existing non-null schema's blank value.
`urlFor` now treats blank transport as unavailable. The legacy `LiveChannelRef` entrances remain so
existing favorites/history are readable and no destructive data migration is required.

## Presentation and zap consistency

`XtreamLivePlaylist` now publishes one immutable, monotonically versioned in-memory snapshot.
Current and relative display lookups return `LiveChannelPresentation`: an opaque redacted content id,
bounded/sanitized title and logo, and the snapshot version. It contains no stream transport and its
string form renders no id or display field.

`IptvIngressSelectionFactory.relativeLive` consumes that presentation directly and constructs the
URL-free provider selection from its exact opaque id. A successful result carries both selection and
presentation from the same playlist snapshot, preventing a concurrent playlist replacement from
silently pairing one channel's metadata with another channel's selection.

## Platform scope

These TV-specific `XtreamLiveStore`, `XtreamLivePlaylist`, and clean IPTV ingress types have no twin
in NuvioMobile, NuvioDesktop, `nuvio-backend`, or `nuvio-web`; cross-repository symbol/file searches
found those platforms genuinely unaffected.
