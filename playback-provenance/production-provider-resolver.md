# Clean playback production provider resolver

Date: 2026-08-27

## Scope

`IptvProviderPlaybackResolverFactory` is the production, Hilt-bound implementation of the clean
core's `ProviderPlaybackResolverFactory` port. Every returned `IptvProviderPlaybackResolver` is
per-session and permanently bound to the exact `PlaybackProfileId` captured when the production
session is created. It is deliberately not connected to a legacy screen or player route.
`PlaybackSession` remains the only caller that may invoke the resolver, after its release barrier
has ended ownership of the previous request.

The resolver performs no health probe and does not fetch account connection limits. It looks up the
bound-profile account, validates the opaque account/item/content identity and source type, then
uses the repository's existing `IptvClientFactory` play API.

The lookup uses `XtreamAccountStore.findForProfile(profileId, accountId)`. It never collects or
reads `ProfileManager.activeProfileId`, so a profile switch while release/tune work is in flight
cannot redirect provider credential resolution into another profile.

## Supported behavior

| Source | Live resolution | Fresh recovery/handoff | Request semantics |
| --- | --- | --- | --- |
| Xtream | Numeric live id through the existing `/live/.../{id}.ts` builder | Re-resolves after the session barrier; the stable formula may produce the same URL | Declares raw MPEG-TS, follows redirects, strips cross-host authorization, preserves connection limit and logical playlist DNS |
| M3U URL/file | Numeric ingested row id through `M3UClient` | Re-reads the stored row; there is no provider mint API | Preserves the arbitrary media URL and playlist-level User-Agent; URL inference supplies only passive evidence |
| Stalker | Numeric live id through the existing static-link/create_link policy | `forceFresh` bypasses the static shortcut for recovery and handoff | Returned media URL is used without leaking portal request cookies/MAC headers to a media/CDN host |
| Xtream catch-up | Existing `CatchUpDialectWalk` using programme bounds, per-account TS/M3U8 preference, correction offset, and remembered legacy winner | Only typed transport/TLS/manifest/demux feedback advances; decoder/render/audio/DRM handoff retains the current dialect | Dialect TS declares raw MPEG-TS; M3U8 declares HLS; panel-default forms stay unknown until extractor evidence |

Known configured DoH providers become the logical `SHARED_APPLICATION_RESOLVER` request policy.
Media3 can consume that application DNS object. libmpv's separately documented plan reports its
system-DNS fallback and never claims DoH was applied.

All request URLs, account/item/content ids, credentials, and media headers remain inside redacting
types. Resolver failures expose only `PlaybackFailure` enums. The Stalker session-cap response maps
to `PROVIDER_CONNECTION_LIMIT`; other untyped legacy null/error results fail closed as `UNKNOWN`.

The provider boundary also derives the compatibility-history scope. It hashes the exact account
connection identity (the same fields used by `XtreamAccount.sameConnectionAs`), stable item/content
identity, content type, and mapped delivery/container/video/audio evidence. Only the SHA-256 scope
key crosses into `PlaybackSession`; no raw provider scope is persisted, logged, or printable.

## Deliberate gaps

- M3U ingestion stores only the URL and playlist-level User-Agent. It does not retain `#EXTVLCOPT`,
  Kodi pipe properties, per-entry cookies, referrer, origin, or headers, so the resolver cannot
  preserve data that the catalog discarded. This needs a versioned M3U media-property model before
  production cutover for providers that require it.
- Existing Stalker `resolveStreamUrl` returns a nullable URL plus a shared `lastResolveError` string,
  not one typed per-call result. The clean resolver serializes its own calls and maps the two known
  constants immediately, but the provider API should eventually return a typed result directly.
- M3U and Stalker catch-up are unsupported by the repository. Only Xtream has a proven archive URL
  dialect API, so other source types fail closed.
- The Xtream panel's `allowed_output_formats` is not persisted with the account. The catch-up walk
  therefore cannot prune its TS/M3U8 ladder from that optional hint.
- The core port currently reports failure feedback but has no rendered-success callback. The new
  resolver can consume winners proven by the legacy coordinator and keeps the active successful
  dialect for the process, but cannot persist a newly proven clean-player winner yet.
- Numeric live/movie rows are supported. Stalker episodes and other non-numeric provider item keys
  use different APIs and remain outside this resolver until their selection contract is defined.

## Verification

- `:app:compileFullDebugKotlin` passed.
- Before compatibility-scope wiring, `IptvProviderPlaybackResolverTest`: 6 passed,
  `PlaybackSessionTest`: 44 passed, and `ArchitectureTest`: 8 passed.
- After compatibility-scope wiring, `:app:compileFullDebugKotlin` passed. The updated focused unit
  task was blocked during test-source compilation by an unrelated in-progress
  `CleanMediaSessionPlayerTest` error; the provider test adds stable-equivalent and distinct-stream
  scope coverage and awaits the unified clean-tree gate.
- NuvioMobile, NuvioDesktop, nuvio-backend, and nuvio-web contain no
  `ProviderPlaybackResolver`, `ProviderPlaybackSelection`, `IptvProviderPlaybackResolver`, or
  `CompatibilityScopeKeyFactory` twin. This
  is a TV-only Android/Hilt adapter over NuvioTV-only IPTV repository APIs; it adds no schema, RPC,
  account payload, or web contract.
