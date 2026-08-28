# VOD clean-session responsibility map — 2026-08-27

## Approved boundary

The existing `PlayerScreen` presentation and control experience remain initially. All playback
ownership underneath that UI moves to the shared clean `PlaybackSession` architecture. This is not
a legacy-player wrapper and not a separate VOD architecture.

```text
PlayerScreen
  -> thin VOD presentation bridge
  -> PlaybackSessionController
  -> PlaybackSession
  -> PlaybackPreferenceResolver / PlaybackPolicy
  -> Media3Engine | MpvEngine
```

The bridge may translate typed UI intent and immutable clean state only. It must not construct an
engine, resolve provider transport, select a decoder, retry, fail over, own a watchdog, or contain a
device quirk.

## Ground truth

- Current VOD: `PlayerScreen -> PlayerViewModel -> PlayerRuntimeController -> legacy Media3/libmpv`.
- Clean core already models `ContentType.VOD`, graph selection, typed failures, VOD watchdog timing,
  bounded recovery, release barriers, generation protection, compatibility history, preferences,
  output policy, and VOD EOF as completion.
- The current clean UI/system facade is intentionally Live V1. It lacks seek, rich timeline facts,
  stable track catalogs/selections, playback rate, external subtitles, and handoff restoration.
- `CleanLivePlaybackHost` rejects non-live selections. The reusable internals must become
  content-neutral, with live and VOD wrappers enforcing their own ingress semantics.
- `PlayerViewModel` currently exposes raw `ExoPlayer`; `PlayerScreen` mounts raw Media3/mpv views and
  invokes engine-specific surface behavior. Those are cutover blockers.

## Responsibility map

| Current responsibility | Current owner | Target clean-slate owner | UI behavior that remains | Classification | Deletion point |
| --- | --- | --- | --- | --- | --- |
| Initial movie/episode/source start | `PlayerRuntimeControllerStartup`, `PlayerRuntimeControllerScrobble` | `VodSourceCoordinator` creates selection/request; `PlaybackSessionController.tune` owns start | Existing loading and resume UX | `RESELECT_GRAPH` | Delete legacy startup after one clean Tune is the only engine start |
| Provider/debrid/torrent source resolution | `PlayerRuntimeControllerStreams`, torrent/debrid helpers | Feature-level `VodSourceCoordinator`; provider resolver where URL-free ingress exists | Same source names, badges and choice | `EXTERNAL_ACTION` then `RESELECT_GRAPH` | Delete direct URL mutation/reinitialize after all source types map to clean ingress |
| Source switch | `switchToSourceStream` and direct release/rebuild | New session generation via `Tune`; session release barrier precedes resolution/start | Same source panel and preserved position policy | `RESELECT_GRAPH` | Delete legacy switch only after release-before-resolve integration tests |
| Episode switch / next episode | streams/metadata/still-watching controller files | `VodEpisodeCoordinator` chooses source; new session generation starts at saved/zero position | Same episode panels, post-play and still-watching | UI browsing + `RESELECT_GRAPH` | Delete engine work from episode functions after clean Tune parity |
| Play/pause | generic `PlayerEvent` dispatcher branches into Media3/mpv | `PlaybackSessionController.pause/resume` | Same remote/button behavior and overlays | `APPLY_IN_PLACE` | Remove engine branches when bridge routes commands |
| Relative/absolute seek | controller branches into Media3/mpv | Core `SeekTo`; UI keeps preview/relative arithmetic | Same seek buttons, scrub preview and seek bar | preview `UI_ONLY`; commit `APPLY_IN_PLACE` | Delete legacy seek after both adapters pass seek contract |
| Timeline/resume | controller polling, saved-progress and engine-specific resume | Generation-bound engine timeline facts; `VodProgressCoordinator`; session resume checkpoint | Same progress bar, saved resume and completion | factual state + `APPLY_IN_PLACE` | Delete polling/resume jobs after lifecycle/handoff tests |
| VOD EOF | legacy player listener/post-play logic | Session terminal reason `EOF`; presentation effect drives post-play | Same completion/next-episode UX | `EXTERNAL_ACTION` | Delete legacy EOF ownership after EOF vs Stop tests |
| Audio track catalog/selection | track controller and engine-specific track lists | Engine-neutral stable track catalog; `SelectAudioTrack(trackId)` | Same labels, languages, codecs and selection UI | `APPLY_IN_PLACE` | Delete track branches after Media3/mpv parity |
| Embedded subtitle catalog/selection/off | track controller and engine-specific selectors | Stable catalog; `SelectSubtitleTrack(trackId)` / `DisableSubtitles` | Same subtitle list and off state | `APPLY_IN_PLACE` | Delete track branches after adapter parity and handoff restore |
| Addon subtitle discovery/download | subtitle repository calls in controller | `VodSubtitleCoordinator` and opaque external-subtitle registry | Same addon list, ordering and errors | `EXTERNAL_ACTION` | Remove repository/network work from playback owner after coordinator parity |
| Addon subtitle attachment | controller mutates source/track selector or mpv | `AttachExternalSubtitle(opaqueId)`; adapter applies or requests graph rebuild | Same selected addon subtitle | `APPLY_IN_PLACE` or `REBUILD_CURRENT_GRAPH` | Delete legacy attachment after both adapters support it |
| Subtitle delay | controller directly changes engine | `SetSubtitleDelay`; effective requirement + adapter | Same remote/overlay adjustment | `APPLY_IN_PLACE` | Delete direct properties after adapter parity |
| Subtitle style | persisted settings plus direct mpv/Media3 view calls | Preference repository + engine-neutral render-style intent; adapter/surface host | Same size/color/bold/outline/offset UI | `APPLY_IN_PLACE` | Delete engine-specific style calls after capability tests |
| Subtitle autosync | controller cue parsing/capture | `VodSubtitleCoordinator` plus optional cue-inspection capability | Same capture/apply UI if capability is present | `EXTERNAL_ACTION` + `APPLY_IN_PLACE` | Delete legacy only after explicit capability parity; never fake support |
| Playback speed | controller calls engine directly | `SetPlaybackRate` command and engine port | Same speed picker/tunneling availability | `APPLY_IN_PLACE` | Delete direct calls after both adapter tests |
| Aspect/scaling | controller plus direct surface/view APIs | UI `VideoLayoutPort`; adapter privately maps engine-specific scaling if needed | Same fit/fill/zoom/original UX | `UI_ONLY` or `APPLY_IN_PLACE` | Delete raw player/view access after generic surface binding |
| AFR/HDR/output matching | legacy AFR preflight plus engine setup | Existing clean preferences, requirements, output controller and output facts | Same setting/info overlay | `APPLY_IN_PLACE` / `REBUILD_CURRENT_GRAPH` | Remove legacy display authority at cutover; exactly one output owner |
| User engine switch | legacy direct teardown and opposite-engine construction | Requested engine preference; policy applies constraints and returns reason | Same action/status, but truthfully reports effective engine | `RESELECT_GRAPH` | Delete direct swap when preference change owns it |
| Decoder selection and software fallback | legacy renderer factory and flags | `PlaybackPreferenceResolver`, `PlaybackPolicy`, graph candidates, adapters | No UI regression; effective reason may be shown | `RESELECT_GRAPH` | Delete all legacy decoder flags/builders at atomic cutover |
| Decoder/renderer/network/audio recovery | legacy watchdogs and fallback ladders | Typed session failure domains and bounded recovery/handoff | Same spinner/error UX using clean status | session-owned | Delete every VOD legacy retry/watchdog before route activation |
| Surface ownership | raw `ExoPlayer`, `PlayerView`, `MpvSurface` exposed to screen/controller | Content-neutral clean surface coordinator and `VideoOutputBinding` | Same video rectangle and OSD layering | host-owned | Delete raw player/controller exposure before cutover |
| Lifecycle | screen pause/resume; retained ViewModel; mixed teardown | VOD host binds destination lifecycle to session lifecycle/release | Resume/background behavior explicitly preserved | host-owned | Delete temporary legacy lifecycle policy after clean VOD route owns release |
| Watch progress/scrobble | controller timers and side effects | `VodProgressScrobbleCoordinator` consumes lifecycle-bound clean snapshots | Same Trakt/local progress behavior | `EXTERNAL_ACTION` | Delete controller jobs after start/pause/stop/final-flush tests |
| Issue reporting | controller assembles report from raw player data | `VodPlaybackReportingCoordinator` consumes sanitized clean diagnostic snapshot | Same report UI and status | `EXTERNAL_ACTION` | Delete raw engine report access after report contract parity |
| External player | screen/controller reads current URL/headers after release | Private one-time `ExternalPlaybackExportPort`; export, release barrier, then launch | Same external-player action and progress handoff | `EXTERNAL_ACTION` | Delete public raw URL/header access after atomic export flow |
| Panels, overlays, focus, timers | `PlayerScreen` and generic controller event switch | Presentation bridge/ViewModel | Preserve current visuals, focus and remote behavior | `UI_ONLY` | Move without changing playback core; delete controller cases when mapped |
| Parental guide, skip UI, stream info | controller/UI | Feature coordinator/presentation; skip commit becomes `SeekTo` | Preserve existing UX | mostly `UI_ONLY`; skip `APPLY_IN_PLACE` | Delete engine access after presentation mapping |
| Torrent statistics | controller/torrent service | source coordinator exposes feature state; not playback policy | Preserve if current source supports it | `EXTERNAL_ACTION`/UI state | Delete mixed engine ownership after source coordinator cutover |

## Required clean contract additions

1. `SeekTo(positionMs)`; relative and preview seeking stay in presentation.
2. Generation-bound timeline facts: position, duration, buffered position and seekability.
3. Start/resume checkpoint semantics carried through initial start, same-graph rebuild, engine handoff,
   lifecycle rebuild and source replacement policy.
4. Stable engine-neutral track descriptors and selected track IDs.
5. Select audio, select subtitle and disable subtitle commands.
6. Opaque external-subtitle attachment; URLs never enter public snapshots.
7. Playback-rate command and effective rate fact.
8. Engine-neutral subtitle render intent/delay capability.
9. Explicit completion reason distinguishing EOF from Stop/Release/Failure.
10. A handoff restoration snapshot containing position, play intent, tracks, subtitle enable/delay,
    style intent, speed and video-layout intent.
11. Secret-safe diagnostics/report export and a private external-player export transaction.
12. Content-neutral host/surface internals with VOD and Live ingress wrappers.

## Forbidden dual authority

- No route may start both a legacy engine and a clean session.
- No shadow playback against a real provider; one-connection accounts make that invalid.
- No clean snapshot may expose a URL, header, cookie, provider credential or DRM secret.
- No UI or presentation bridge may import Media3/libmpv engine APIs.
- No adapter may retry, refresh provider links, select another engine or own recovery budgets.
- No VOD cutover lands while any `PlayerRuntimeController` VOD retry/decoder path remains reachable.
