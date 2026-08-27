# Clean live player screen — 2026-08-27

## Boundary

`CleanLivePlayerScreen` is an isolated Android-TV Compose presentation beside the frozen legacy
player. It is not registered in the `NavHost` and does not construct a playback host, session,
engine, provider request, or surface child.

Its only playback input is the secret-free `LivePlaybackUiState`. Display metadata enters as three
caller-sanitized strings: title, subtitle, and station. Playback interactions leave as
engine-neutral pause, resume, retry, previous-channel, next-channel, and exit-request callbacks.
The exit callback is deliberately called `onExitRequested`: the future route owns the serialized
`CleanLivePlaybackHost.release()` barrier and may remove the screen only after that barrier returns.

## Surface ownership

The first Compose layer is an `AndroidView` that creates one initially empty `FrameLayout` and
hands it to `onSurfaceOwnerReady` exactly once for that view instance. Compose does not add, remove,
select, secure, attach, or detach a child surface. Those responsibilities remain exclusively with
`CleanLiveSurfaceCoordinator`. Title, controls, spinner, and status are Compose overlays above the
owner and cannot be confused with a player surface.

## TV interaction and lifecycle

- The existing reference-counted `ImmersivePlaybackGate` hides app-level chrome while composed.
- The raw owner requests `keepScreenOn` only while the engine-neutral presentation says playback is
  intended and is either playing or visibly starting/recovering.
- Media play/pause keys use authoritative `LivePlaybackUiState.playWhenReady`.
- D-pad UP/DOWN dispatch previous/next channel only while controls are enabled. A key is consumed
  only when that action is actually dispatched; otherwise normal TV focus/navigation can handle it.
- Horizontal focused controls provide previous, play/pause, retry, next, and exit actions. Retry is
  conservative: normalized playback/preview failures permit it; terminal stream unavailability does
  not pretend to be retryable.
- Android back emits only the route-owned exit request. The screen never releases or navigates.

## Stable, localizable presentation

Spinner visibility comes directly from `LivePlaybackUiState`. Every `LivePlaybackUiStatusCode`,
`PreviewUnavailableReason`, `StreamUnavailableReason`, and `FailureCode` is mapped exhaustively to a
static string resource. No provider text, engine exception, URL, header, credential, or query value
can enter the status surface.

## Verification and platform audit

Pure unit tests cover screen-on policy, conservative retry, status/error precedence, exhaustive
resource mappings, enabled/disabled zap consumption, and media-key playback intent. A source-level
architecture test rejects Media3, libmpv, provider/network, request/session/controller, legacy view,
and legacy ViewModel dependencies, and pins the empty `FrameLayout` handoff with no Compose child
mutation. The shared architecture classifier now explicitly treats `ui/screens/player/clean/**` as
clean UI rather than frozen legacy orchestration, while still rejecting imports from `PlayerScreen`,
runtime controllers, and the other legacy player packages.

NuvioMobile, NuvioDesktop, `nuvio-backend`, and `nuvio-web` contain no Android-TV `FrameLayout`,
remote-key, or Compose `AndroidView` twin for this screen. They are genuinely unaffected by this
unwired TV-only presentation primitive.
