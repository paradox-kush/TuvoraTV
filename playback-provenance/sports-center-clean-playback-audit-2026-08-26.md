# Sports Center clean-playback integration audit — 2026-08-26

## Scope and verdict

This is a code-grounded audit of the Android TV/Fire TV Sports Center playback path. It does not
change production routing or the clean playback core.

Sports Center currently reaches the legacy `Screen.Player` route with a provider URL. It therefore
does **not** use the clean `NavigationPlaybackInput -> PlaybackSession` path and does not inherit the
clean session's release barrier, phase watchdog, engine handoff, or live reconnect ownership.

The final cutover must not be a callback rename. The current Sports code resolves and, for some
sources, health-probes the selected stream before navigation. A safe clean cutover needs a deferred,
opaque provider selection that the serialized playback owner resolves only after the previous
engine has affirmatively released its provider connection and surface.

## Current TV launch graph

### Entry and match overlays

The TV app has one Sports destination, `Screen.SportsHub`:

- `MainActivity.kt` exposes it as a root/sidebar destination.
- `NuvioNavHost.kt` constructs `SportsHubScreen` at the `Screen.SportsHub` destination.
- `SportsHubScreen.kt` exposes raw playback callbacks:
  - `onPlayChannel(title, streamUrl, contentId)`
  - `onPlayCatchUp(url, title, contentId, startMs, endMs)`
  - `onOpenDetail(contentId, type)` for recordings.
- All three match-sheet presentations call the same ViewModel methods:
  - fixture overlay at `SportsHubScreen.kt:195-196`
  - league overlay at `SportsHubScreen.kt:223-224`
  - main match sheet at `SportsHubScreen.kt:620-621`

There is no separate TV home-screen Sports play path. The TV home package contains no Radar/Sports
launch callback; Sports is entered through the sidebar destination. Recordings are not direct
streams: `onOpenDetail` navigates to the native detail pipeline.

### Live match path

```text
SportsHub match row
  -> SportsHubViewModel.playMatch
  -> RadarChannelMatcher.playbackUrlFor
       Xtream: listed `/live/{user}/{pass}/{id}.ts` URL
       M3U: listed arbitrary media URL
       Stalker: resolve a static command or mint a fresh create_link URL
  -> optional HttpURLConnection health probe (Xtream/M3U only)
  -> RadarChannelMatcher.ensurePlayable (legacy registry bridge)
  -> LivePlaylist.set (Sports-specific zap list)
  -> onPlayChannel(title, rawUrl, contentId)
  -> Screen.Player.createRoute(rawUrl, contentType="live", contentId)
  -> legacy PlayerScreen / PlayerViewModel / PlayerRuntimeController
```

`SportsHubViewModel.playMatch` tries up to six candidates. A candidate with a listed URL is opened by
an independent `HttpURLConnection`, follows redirects, and reads one body byte. It sends no
playlist-specific user agent, cookies, referer, origin, or custom headers. A Stalker row deliberately
skips this probe because `playbackUrlFor` has just minted a single-use link.

The probe closes before navigation, but it is still an extra provider open before session ownership.
It can consume a provider token/capacity, produce a false negative when playback headers are needed,
and race rapid repeated selections. The clean phase watchdog is the correct owner of byte/track/
decoder/frame progress after a single engine open; it must not be preceded by this mandatory probe.

### Replay/catch-up path

```text
SportsHub replay row
  -> SportsHubViewModel.playReplay
  -> RadarChannelMatcher.beginReplay
  -> CatchUpPlaybackCoordinator.begin (Xtream only; dialect walk is single-flight per account)
  -> register first replay URL in XtreamItemRegistry
  -> onPlayCatchUp(rawUrl, bounds, contentId)
  -> Screen.Player.createRoute(
       contentType="live", isCatchUp=true, catchUpStartMs, catchUpEndMs)
  -> legacy PlayerRuntimeController catch-up handling
```

The clean model has an explicit `ContentType.CATCH_UP`. The cutover must use it rather than preserving
the legacy `LIVE + isCatchUp` encoding. Otherwise live no-EOF/reconnect/zap semantics can be applied
to a finite replay. Catch-up bounds, initial position, and the dialect advance contract must be
represented explicitly at the provider/application boundary before this path can cut over.

### Legacy recovery and zapping reached by Sports

- `PlayerRuntimeControllerIptvLinkRefresh.kt` refreshes namespaced IPTV ids on 401/403/410 through
  `StreamRepository.refreshIptvStreamUrl(contentId, forceFresh=true)`.
- `StreamRepositoryImpl.refreshIptvStreamUrl` parses the account/source from the content id and calls
  the source-specific `IptvClient.resolveStreamUrl`.
- Stalker can therefore mint a new `create_link` after an eligible failure; Xtream rebuilds the
  stable TS URL; M3U looks up its stored URL.
- `LivePlaylist.set` lets legacy UP/DOWN zap among the current fixture's matched broadcasts.
- catch-up failures may advance `CatchUpPlaybackCoordinator`'s dialect walk in the legacy player.
- playlist-specific DNS preparation is performed by the legacy player path, outside Sports itself.

These behaviors are required parity inputs, not authorities to copy into an engine adapter. Fresh
link resolution, catch-up dialect movement, and zap selection belong above the adapters and must
enter the clean session's serialized command lane.

## Current return-navigation behavior

All Sports live and replay launches navigate from `sports_hub` to the legacy `player/...` route.
`NuvioNavHost.returnToLiveGuideOrPop` determines the return destination from `contentType == "live"`:

1. pop to `Screen.SportsHub` if it exists on the back stack;
2. otherwise pop to `Screen.XtreamHub`;
3. otherwise navigate to `Screen.XtreamHub`.

The same helper is called for player BACK, playback-ended, and error-back. This retains the Sports
hub ViewModel and match-sheet state when the Sports route is still present, but the origin is inferred
from back-stack presence. A Sports replay is also treated as live for return purposes.

The clean cutover needs an explicit, non-secret launch origin/return token (for example
`SPORTS_HUB`, `IPTV_GUIDE`, or `HOME_SPORTS`), owned by presentation/navigation. PlaybackSession must
not inspect navigation state, and navigation must not infer playback semantics from a raw route.

## Transport and provider evidence matrix

| Source | Live URL timing/shape | Media-request metadata currently preserved by Sports | Refresh semantics | Connection-limit evidence |
| --- | --- | --- | --- | --- |
| Xtream | Browse-time stable `/live/.../{id}.ts`; `XtreamClient` explicitly builds live as MPEG-TS | No headers/cookies/referer/origin; credentials are URL path segments | Formula rebuild; `forceFresh` does not change the URL | `accountInfo` can return `activeConnections` and `maxConnections`, but neither is persisted/carried by the Sports candidate or route |
| M3U URL/file | Browse-time arbitrary URL from ingested M3U | **Gap:** optional account UA is used to fetch the playlist/XMLTV but is not carried into the selected media request; no cookies/referer/origin/custom headers are modeled | DB lookup of the stored media URL; `forceFresh` is not meaningful | No account-info support; unknown |
| Stalker/MAG | Blank browse URL; play-time static-command decision or fresh single-use `create_link` | Portal API calls use MAG UA, X-UA, referer, MAC/session cookies and authorization inside `StalkerSession`; after `extractStreamUrl`, Sports passes no media headers. Code therefore assumes the returned media URL is independently playable; it does not prove all portals share that property | Fresh `create_link` on forced refresh; session-limit and link-fault errors are distinguishable | Portal session limit can be reported as a specific create-link refusal, but numeric max/active values are unknown |
| Xtream catch-up | URL minted by a single-flight `CatchUpPlaybackCoordinator` dialect walk; TS first unless account prefers M3U8 | No playback headers/cookies/referer/origin in the Sports handoff | Only transport-shaped failures may advance the dialect; decode failures must not | Same uncached Xtream account-info gap |

Additional observations:

- There is no DRM metadata in Sports channel/replay models.
- `CandidateChannel` contains playlist id, content id, stream id, URL, EPG id, and archive flag only.
- `Screen.Player.createRoute` embeds URL and optional headers in the navigation string. URL encoding is
  not secret protection: Xtream credentials and signed media tokens remain in back-stack state and
  route diagnostics. The clean path must pass opaque state, never a provider URL route argument.
- Redirect, cross-host authorization, TLS, DNS, timeout, MIME, and declared container/delivery facts
  are not carried by the current Sports callback.
- The legacy DoH helper may itself perform a redirect GET before playback. Any replacement must keep
  the clean request's logical DNS policy without opening a second media body. V1 Media3 can apply the
  application resolver; libmpv truthfully records its approved system-DNS fallback.

## Required clean integration

The target is one fullscreen playback presentation and one session, regardless of whether selection
came from the IPTV guide or Sports:

```text
Sports UI selection (opaque account/source/content id + display metadata + return origin)
  -> provider playback launch boundary
  -> serialized release-before-resolve seam
  -> source-specific resolver mints/looks up the current URL and transport metadata
  -> NavigationPlaybackInput
  -> PlaybackRequestMapper
  -> PlaybackRequest + StreamEvidence
  -> PlaybackSession Tune/Zap(FULLSCREEN)
  -> requirements / graph selection
  -> Media3 or libmpv facts-only adapter
  -> common fullscreen UI + watchdog + reconnect + release barrier
```

### Required ownership

1. **Sports UI/ViewModel** selects a channel/replay and publishes an opaque launch intent. It owns
   fixture ordering and display state, not URLs, probes, retries, or engine choice.
2. **Provider launch/resolution boundary** resolves account/source/content id, supplies a fresh URL,
   source-required headers/UA/referer/cookies, DNS/network intent, declared TS/HLS evidence, and a
   conservative provider connection limit. It also owns Stalker create-link and catch-up dialect
   resolution.
3. **PlaybackRequestMapper** performs the existing passive, secret-safe normalization into
   `PlaybackRequest` and `StreamEvidence`. It performs no I/O or probing.
4. **PlaybackSession** serializes tune/zap/reconnect, releases the previous engine before any provider
   reopen, resolves fresh requests, runs the phase/runtime watchdog, and allows one recovery action
   per incident.
5. **Engine adapters** receive the resolved request/graph, report only bytes/tracks/decoder/renderer/
   frame/end/error facts, and affirmatively prove release. They own no Sports or provider behavior.
6. **Fullscreen presentation/navigation** observes the same session snapshot as every other live
   launch. It owns controls, reconnect/error UI, immersive mode, and return-to-origin behavior.

### One-connection and rapid-zap rules

- No Sports health probe may open the chosen media before the session.
- Stalker `create_link`, a catch-up first URL, or any other expiring/single-use URL must be minted
  only after the previous engine release barrier succeeds.
- A new match/channel selection coalesces as the latest zap intent; superseded selections must not
  mint URLs.
- Resolution failure must not start an engine. Release failure/hard-abort failure remains fail-closed
  and must not resolve or open the next provider request.
- Sports fixture zapping may retain `LivePlaylist` as UI ordering during migration, but the actual
  transition must become `PlaybackSession` zap with fresh provider resolution—not legacy in-engine
  URL replacement.
- Until a cached trustworthy maximum exists, IPTV launches should use a conservative connection
  limit of one. Do not call `accountInfo` on every tune merely to obtain the maximum; that would add
  latency/egress and still would not cover M3U/Stalker.

## Deferred provider-selection seam (implemented in clean core)

`NavigationPlaybackInput` still represents the concrete result of provider resolution, but
`PlaybackCommand.Tune/Zap` now also accepts `ProviderPlaybackSelection`: a URL-free source/account/
item/content identity with typed content/catch-up/provider-limit/evidence metadata. All identifiers
use redacted value types.

`ProviderPlaybackResolver` runs inside `PlaybackSession` only when the reducer's release barrier has
advanced to request resolution. It returns the concrete request and evidence together. The session
validates content identity/type/connection-limit consistency, merges declared evidence by
provenance, cancels superseded resolution, rejects stale generations, and uses the same provider
resolver again for fresh recovery, live reconnect, and deferred engine handoff. Every fresh result
is committed to the generation's current request/evidence/requirements before reuse, preventing a
later graph rebuild from reopening a consumed single-use URL. Concrete migration requests receive
the same identity/ownership validation and their summary is recomputed from the accepted request.

`ProviderResolutionContext` supplies only typed, secret-safe prior-failure feedback. Catch-up
dialect choice stays resolver-owned: transport/TLS/manifest/demux failures may advance its TS/HLS
sequence, while decoder/renderer/audio/DRM failures are explicitly ineligible to advance it.

The existing concrete-request command remains as a temporary migration seam. Production cutover
still needs the Android IPTV implementation of `ProviderPlaybackResolver` and the Sports/UI mapping;
neither belongs in an engine adapter.

## Concrete production cutover and deletion targets

### Replace at cutover

1. `SportsHubScreen.kt`
   - replace raw URL callbacks with opaque live/catch-up launch intents;
   - keep UI display and match ordering only.
2. `SportsHubViewModel.playMatch`
   - remove `playbackUrlFor` from the pre-navigation path;
   - remove the direct `HttpURLConnection` probe and dead-channel decisions based on it;
   - publish the selected provider identity/content id to the clean launch boundary.
3. `SportsHubViewModel.playReplay`
   - stop beginning/minting catch-up before the session barrier;
   - publish replay identity/bounds and let provider resolution begin in the serialized lane.
4. `NuvioNavHost.kt` Sports destination
   - delete both Sports calls to `Screen.Player.createRoute`;
   - enter the common clean fullscreen presentation with an explicit Sports return origin;
   - map live to `ContentType.LIVE`, replay to `ContentType.CATCH_UP`.
5. Sports zapping
   - replace legacy raw-list/in-engine replacement with session `Zap` through the same deferred
     provider resolver and barrier.
6. production clean request resolver
   - resolve namespaced IPTV content ids by account/source;
   - preserve M3U playback UA and any future per-entry media properties;
   - return source-declared TS/HLS evidence and conservative connection limit;
   - surface Stalker session-limit as a normalized provider/auth/resource failure rather than a dead
     channel.
7. clean fullscreen navigation owner
   - replace back-stack inference with an explicit return origin while retaining Sports hub state.

### Delete only after parity is proven

- `SportsHubViewModel.isChannelPlayable`, `isStreamAlive`, `radarChannelNeedsHealthProbe` use in the
  Sports launch lane, `PROBE_CAP`, and `PROBE_TIMEOUT_MS`.
- `RadarChannelMatcher.ensurePlayable` as a bridge for Sports playback, once the clean resolver no
  longer depends on `XtreamItemRegistry` registration. Do not delete it if another legacy caller
  remains.
- Sports-specific `Screen.Player.createRoute` URL/catch-up construction.
- Sports dependence on `PlayerRuntimeControllerIptvLinkRefresh`, legacy catch-up advancement, and
  legacy zap replacement after equivalent clean integration tests pass.
- the legacy `returnToLiveGuideOrPop` Sports inference after all live launch origins use the clean
  navigation contract. The helper may remain for uncut legacy IPTV launches.

`Screen.Player` and the legacy runtime-controller files are shared by non-Sports playback and must
not be deleted as part of a Sports-only cutover.

## Required tests before enabling the route

Pure/application tests:

- Xtream, M3U, Stalker, and catch-up selections produce secret-safe provider launch intents.
- M3U account UA becomes media-request UA; no URL or credential appears in `toString`/telemetry.
- source-declared Xtream live evidence is raw/progressive MPEG-TS; arbitrary M3U is not guessed from
  account type alone; catch-up evidence follows the selected dialect.
- return origin is Sports for back, terminal replay completion, and user-visible fatal failure.
- Stalker session-limit is not marked as a dead channel.

Session integration tests with fakes:

- current provider release completes before Stalker create-link or catch-up begin is invoked;
- release/hard-abort failure prevents resolution and the next engine start;
- six rapid Sports selections mint only the final URL and never exceed one active provider request;
- Sports live uses fullscreen profile, live EOF reconnect, and the common phase/runtime watchdog;
- Sports catch-up uses `CATCH_UP`, does not live-reconnect on normal EOF, and retains bounds;
- UP/DOWN zap resolves after the barrier and preserves the fixture channel order;
- provider link refresh re-enters the same resolver/session lane with no adapter-owned retry;
- common fullscreen UI shows reconnect/failure state and returns to the retained Sports hub.

No engine-specific Sports test should be necessary beyond the already-required adapter contract and
device certification: Sports must not select or configure Media3/libmpv directly.

## Cross-platform twin audit

The clean architecture and this cutover are TV-only, as approved. The semantic Sports feature has
twins that must be revisited if shared provider-selection behavior changes:

- **NuvioMobile**
  - `composeApp/src/commonMain/kotlin/com/nuvio/app/features/radar/SportsHubScreen.kt`
  - `RadarChannelMatcher.kt`, `RadarHomeSection.kt`, `RadarHomeSportsSection.kt`, `RadarHubContent.kt`
  - `App.kt` and `HomeScreen.kt` pass a content id through `onPlayLiveChannel`/`onPlaySportsChannel`.
  - Unlike TV, Mobile also exposes Sports matches from Home.
- **NuvioDesktop**
  - the same commonMain files and callback shapes exist, including the Home Sports section.
- **nuvio-backend / nuvio-web**
  - no Sports player launch or engine route was found; no cutover counterpart exists.

No Mobile/Desktop production edit belongs in this TV clean-player work package. However, a later fix
to M3U media-header preservation, Stalker provider descriptors, probe behavior, or shared Radar
models is cross-platform behavior and must be ported/tested under the repository `AGENTS.md` rule.

## Open decisions before implementation

1. Choose the application-level launch-origin contract and state restoration behavior.
2. Decide where a cached provider connection maximum lives. Default unknown IPTV sources to one;
   do not fetch account info per tune.
3. Define M3U media-request property support. Today only playlist-fetch UA exists; per-entry headers,
   cookies, referrer, and Kodi/VLC-style properties are not modeled.
4. Choose and test the Android resolver's provider-specific TS/HLS dialect ordering. Core now
   provides typed advance eligibility; dialect state remains outside core and must not be smuggled
   through `ContentType.LIVE`.
5. V1 explicitly uses system DNS for libmpv when a playlist is configured for application DoH. The
   logical request retains `SHARED_APPLICATION_RESOLVER`; the libmpv adapter records its explicit
   system-fallback mode rather than claiming DoH was applied or inventing a proxy.

Once these are approved, Sports can be cut over without creating a second player architecture: it
becomes another source of clean fullscreen session commands.
