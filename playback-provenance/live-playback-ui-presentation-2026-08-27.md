# Clean live playback UI presentation — 2026-08-27

## Scope

This change adds the pure presentation contract needed by a future clean TV guide/fullscreen host.
It does not wire a production route, construct a player, retry a stream, resolve a provider link, or
localize user-facing text.

`LivePlaybackUiPresenter` maps the existing secret-free `PlaybackSnapshot` to a compact immutable
`LivePlaybackUiState` containing:

- spinner visibility;
- stable bottom status and error reason codes;
- transport-controls availability;
- guide-to-fullscreen action availability;
- authoritative play intent, playing, and paused facts;
- preview availability kept separate from terminal stream availability.

## Decisions

- Resolving, graph selection, Surface attachment, startup, in-place recovery, one-time handoff, and
  indefinite live reconnect display a spinner. A factual mid-playback buffer also displays it.
- Live reconnect suppresses the triggering failure while recovery remains active. The state machine
  already converts live EOF to `LIVE_RECONNECTING`; presentation never treats EOF as completion and
  does not invent an end reason or retry counter.
- Source-confirmed terminal stream availability wins over all other errors and disables controls.
- A preview-only failure uses a `PreviewUnavailableReason` only in the guide profile, without
  claiming the provider stream is terminally unavailable. It disables preview transport controls
  but keeps the fullscreen action available while the stream remains non-terminal. Fullscreen
  presentation ignores stale guide-only preview state. Idle, releasing, and stopped sessions do
  not expose the fullscreen action.
- A handoff or active recovery shows its status rather than the triggering failure. A normalized
  `FailureCode` appears only after playback becomes terminally failed.
- Paused state comes from authoritative `PlaybackSnapshot.playWhenReady`, not from `isPlaying` and
  not from presentation-owned shadow state.
- All bottom values are enums/structured codes. URL, credential, provider message, decoder name,
  exception text, and free-form status strings cannot enter this contract.

## Verification

- The spinner and bottom-status tests iterate every `PlaybackState`; buffering is also covered as a
  factual degraded-playback condition.
- Focused tests cover live EOF/reconnect, terminal source unavailability, preview-only failure,
  playing/paused intent, release/stop, handoff suppression, generic terminal failure, and every
  preview/stream reason enum.
- The architecture firewall rejects Android, Media3, libmpv, provider implementation, legacy IPTV,
  and production UI imports from the live presentation file.
- Contract reflection proves the presentation types expose no free-form `String` field.
- Mobile, Desktop, backend, and web do not contain this Android TV clean presentation contract and
  are unaffected.

The bounded one-worker/in-process gate compiled the TV main source and passed all 12 focused live
presentation tests. The presentation architecture firewall also passed. The combined architecture
suite remained red only on a separate, concurrently uncommitted `ProductionPlaybackComposition`
fork-feature firewall crossing; this change neither owns nor modifies that file.
