# Clean live guide playback owner — 2026-08-27

## Scope

This increment adds the Hilt playback owner and cuts the production IPTV guide over to it.
`XtreamLiveGuideViewModel` remains the catalog/EPG/favorites/catch-up owner, while
`XtreamLiveGuideScreen` passes stable live content ids to the clean owner and renders its neutral
snapshot/UI presentation.

## Ownership model

`CleanLiveGuidePlaybackViewModel` owns exactly one `CleanLiveHost` and one Android surface owner at
a time. Initial channel identity comes from `LiveChannelSelectionPort`; neighbour identity comes
from `LiveChannelNavigationPort`. Both return URL-free `LiveChannelTarget` values. The owner sends
only the target selection and sanitized media-session metadata to the shared host facade.

- Initial playback uses `SessionProfile.GUIDE`.
- A direct guide tune and remote zap reuse the existing host.
- Promotion and collapse call `changeProfile(FULLSCREEN)` and `changeProfile(GUIDE)` on that same
  host; they never construct a second player.
- A different surface owner first completes the old host's affirmative release barrier, then
  creates and retunes one replacement host.
- The optional guide preview viewport now passes through `AndroidCleanLiveHostInput` to
  `CleanLivePlaybackHost`, so playback policy can budget decoder output for the actual preview.

## Identity, history, and profile safety

The active numeric profile is captured as `PlaybackProfileId` and checked before and after
selection, host creation, accepted tune/zap, and profile-mode changes. A mismatch releases the host
before publishing a typed `PROFILE_CHANGED` rejection.

History is armed only with the generation returned by an accepted host tune or zap. It is recorded
once only when that exact generation reports its first rendered video frame. A later generation
clears the pending identity instead of guessing that retry output belongs to it. History failures
remain best effort and cannot fail playback.

## Command and release semantics

Initial attach captures a generation/profile basis, performs provider-neutral selection outside
the owner mutex, then reacquires ownership and validates the same generation/profile before it may
create a host. A hung initial lookup therefore cannot block detach or terminal release. The latest
channel request received during initial selection is retained and applied on the host's zap path
after its initial tune.

Tune and zap capture the current host/target/profile under the owner mutex, perform provider-neutral
identity lookup outside that mutex, then reacquire it and require the same host, target, and profile
before accepting a command. A slow provider lookup therefore never blocks release or surface
rebind. Remote repeat input uses a conflated channel: one lookup/accepted zap runs at a time, with
at most the newest pending direction retained. Every direct channel change after initial tune uses
the host's zap path and its release barrier.

Compose-facing tune and GUIDE/FULLSCREEN transitions are non-suspending requests launched on the
ViewModel-owned scope. Direct tune requests use their own conflated channel and one worker, so a
slow older lookup cannot commit after a newer selection; at most the latest pending selection is
retained. Cancellation of the calling composition coroutine cannot split an accepted host command
from target/profile state publication.

Explicit exit waits for the clean host release barrier. `onCleared` uses the ViewModel-owned scope
to retry transient release failures with exponential delays capped at five seconds; ownership is
not cancelled until release succeeds.

Guide composition disposal uses a separate nonterminal `detachGuide()` barrier. It releases the
host and empties the Android surface owner but retains the accepted target/profile identity, so a
later composition can create exactly one replacement and retune without constructing a parallel
player. Public pause/resume/retry/profile/detach requests contain host failures in the ViewModel
scope and publish a coarse guide failure rather than throwing into Compose.

## UI boundary

`CleanLiveGuidePlaybackState.Ready` contains only `LiveChannelTarget`, `PlaybackSnapshot`,
`LivePlaybackUiState`, and `SessionProfile`. It exposes no provider URL, engine type, decoder API,
or transport/client object. Provider errors are collapsed into stable guide failure enums.

The production screen remembers one `FrameLayout`, changes only its geometry between GUIDE and
FULLSCREEN, and never imports Media3, libmpv, a player view, a playback URL, MIME selection, freeze
watchdogs, or lifecycle restart callbacks. BACK collapses the same host through `changeProfile`;
remote UP/DOWN uses the clean owner's serialized relative-zap path. The catalog publishes the
active profile's URL-free `XtreamLiveChannelIdentity` lineup before exposing a loaded channel list.

## Focused verification

The new tests cover guide-profile initial tune and viewport propagation, same-host tune/zap and
profile promotion/collapse, exact-generation history and superseding-generation rejection,
serialized/conflated zapping, release-before-recreate surface rebind, profile-race release, and
the capped `onCleared` release retry cadence. They also prove release can complete during a blocked
channel lookup and caller cancellation cannot cancel accepted tune/profile work.
The delayed-first tune case proves the final accepted target is the latest queued selection.
Additional coverage proves terminal release is not blocked by an initial selection lookup, a tune
arriving during attach is retained, detach empties the surface before reattach, public command
failures remain contained, and source-level guide architecture cannot regain direct engine/URL
ownership.

## Removed guide playback compatibility layer

The production cutover removes the now-unreferenced guide-only reactive layer:

- `GuidePreviewFreezePolicy.kt` and test
- `GuidePreviewOwnership.kt` and test
- `LiveCodecReusePolicy.kt` and test
- `LiveGuideSurfacePolicy.kt` and test
- `LivePreviewRenderersFactory.kt`
- `LiveStreamEndRetryPolicy.kt` and test
- `iptv_live_guide_texture_player_view.xml`

Legacy catalog, EPG, favorite, recent, and catch-up replay behavior remains in
`XtreamLiveGuideViewModel`; those are data/product responsibilities, not playback ownership.

Static verification for this parallel lane consists of `git diff --check`, symbol inspection, and
forbidden provider-transport/engine ownership scans. Gradle is intentionally reserved for the root
integration lane.

## Platform applicability

This is a TV-only guide ownership boundary. NuvioMobile and NuvioDesktop do not contain the Android
TV guide surface/host path; nuvio-backend and nuvio-web do not own playback surfaces. No parallel
platform file is applicable.
