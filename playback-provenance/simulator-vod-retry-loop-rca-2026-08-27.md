# Android TV simulator VOD retry-loop RCA — 2026-08-27

## Release disposition

Release remains stopped. This incident was discovered while validating the separate Live Guide
playlist-switch repair. It is a legacy VOD-player defect, not a failure inside the clean Live TV
`PlaybackSession` pipeline.

## Reproduction

- Device: Android TV API 36 arm64 simulator (`c2.goldfish.*` codecs).
- Content: 3840x1604, 23.976 fps, 10-bit HEVC Main 10/HDR MP4 VOD.
- Visible symptom: the loading UI alternates between stream construction and subtitle fetching.
- Additional symptom: after navigating back to Sports, the VOD retry loop continues in the
  retained player ViewModel.

No provider URL, credentials, request headers, or account identifiers are recorded in this RCA.

## Trace findings

Media3 reports the selected track as `NO_EXCEEDS_CAPABILITIES`. The simulator then fails
`c2.goldfish.hevc.decoder` with `MediaCodec.CodecException` error `0xe`. This is a deterministic
video-decoder capability failure for this simulator/content combination.

Every subsequent `SubtitleRepository: Fetching subtitles` line belongs to a newly initialized
player attempt. Subtitle discovery is not the cause. The observed cycle was:

1. build the same VOD media source;
2. fetch the same subtitle candidates;
3. initialize the same unsupported HEVC decoder;
4. receive the same video-renderer failure;
5. release and rebuild immediately;
6. repeat without a terminal state.

## Root causes

### V1 — an audio fallback budget was used for a video-renderer failure

The legacy error handler treated generic decoding failures as candidates for the safe-audio / PCM
/ audio-disabled ladder. A `MediaCodecVideoRenderer` failure must not enter an audio-recovery
ladder.

### V2 — fallback attempt state was not monotonic across rebuilds

`hasTriedAudioPcmFallback` represented both player configuration and attempt history. The error
handler set it, but the next `initializePlayer` reset it. Therefore the PCM rung was considered
unspent on every generation and the audio-disabled/terminal states were unreachable.

This explains the existing defect; it is not an instruction to add another ladder to the legacy
controller. Recovery attempt state in the clean session must be selection-scoped and monotonic:

`NONE -> SAFE_AUDIO -> PCM -> AUDIO_DISABLED -> EXHAUSTED`

Internal release/rebuild retains the state. A fresh user source/episode selection creates a new
session generation, even if it resolves to the same URL.

### V3 — retained navigation state outlived the player screen

Drawer navigation saves the destination state and retains its ViewModelStore. `PlayerScreen`
handled pause/resume but did not release on terminal destination destruction. Consequently a
queued `errorRetryJob` could rebuild playback while Sports was foreground.

The cleanup boundary is destination `ON_DESTROY`, except while the Activity is changing
configuration. `ON_STOP` is intentionally not terminal because ordinary app backgrounding should
remain resumable.

## Relationship to the Live Guide incident

VOD routes through the legacy chain:

`PlayerScreen -> PlayerViewModel -> PlayerRuntimeController -> ExoPlayer/libmpv`

Live Guide preview routes through the clean chain:

`XtreamLiveGuideScreen -> CleanLiveGuidePlaybackViewModel -> CleanLivePlaybackHost -> PlaybackSession -> Media3Engine/MpvEngine`

Therefore `PreviewUnavailable(reasonCode=GUIDE_RENDER_PATH_UNAVAILABLE)` is a Live Guide
presentation state and cannot be emitted by VOD. The VOD incident nevertheless matters to Live TV
validation because a leaked legacy player can retain decoder/network resources behind another
screen.

## Approved VOD integration boundary

Only the current `PlayerScreen` presentation and user-facing control experience remain during the
VOD cutover. The entire playback pipeline underneath it moves to the shared clean architecture:

`PlayerScreen -> thin presentation bridge -> PlaybackSessionController -> PlaybackSession -> PlaybackPolicy -> Media3Engine/MpvEngine`

`PlayerRuntimeController` must not receive new decoder, retry, handoff, provider, watchdog, or
device-quirk logic. The destination-destroy release is the single temporary containment fix allowed
before cutover because it stops the currently observed background resource leak.

## Validation contract

- A video renderer failure remains in the clean `VIDEO_DECODER` or `VIDEO_RENDERER_SURFACE` domain
  and never advances audio recovery.
- Recovery ownership and attempt budgets survive graph rebuilds in `PlaybackSession`; no legacy
  player-object flag owns them.
- Fresh source/episode selection creates a new session generation, including same-URL reselection.
- Destroying the player destination cancels pending retries and releases both engines unless the
  Activity is changing configuration.
- Simulator rejection of this 4K HEVC sample is surfaced by the clean session as a stable typed
  error or a bounded valid graph handoff; it never becomes an unbounded rebuild loop.
- After atomic cutover, no VOD playback/recovery path remains in `PlayerRuntimeController`.

## 2026-08-27 follow-up on the current production VOD route

The rebuilt arm64 debug APK was exercised on the API 36 TV simulator with the IPTV profile's
progressive H.264 MP4 VOD. This is current-route regression evidence, not clean-session cutover
certification.

- The apparent fully black frame before playback was the dark detail/transition artwork. UI
  hierarchy inspection proved the destination and focused source control were still present.
- Selecting the source produced a real 1920x800 H.264 frame and the existing VOD control overlay.
- Startup exposed a caught `NullPointerException`: `updateMediaSessionMetadata()` inserted a
  metadata-only `MediaItem` before the real media source existed. Media3 then attempted to create a
  source for an item without a URI. The placeholder mutation was removed; initial metadata now
  travels on the real media source, and later metadata updates replace only that real item.
- Post-fix trace: MediaSession metadata updated without an exception, the
  `c2.goldfish.h264.decoder` initialized, and video rendered.
- Continue Watching exposed a contract mismatch: the card intentionally displays any real local
  checkpoint, but playback restored only entries at or above 2%. A 54-second checkpoint was shown
  as `Resume` and then started from zero. Restore eligibility now accepts any non-complete positive
  position/percentage. Unit coverage includes the below-2% case.
- Final simulator proof restored position `439738ms`; subsequent playback telemetry advanced from
  `439s` instead of zero. The app was force-stopped after validation to release the provider's
  one-connection slot.
- The test stream intermittently rebuffered on the simulator/provider path but continued rendering;
  this does not prove the unsupported 4K HEVC clean recovery case.

The clean Media3 adapter also now reports unsupported subtitle-delay processing as
`SUBTITLE_OUTPUT_UNSUPPORTED/SUBTITLE`, not `AUDIO_OUTPUT_FAILED/AUDIO`. This prevents subtitle
requirements from entering an audio fallback domain during the future cutover.

## 2026-08-27 terminal capability-failure containment

The same 4K HEVC Matroska selection was replayed on the API 36 TV simulator after the initial RCA.
Media3 again classified the selected video track as `NO_EXCEEDS_CAPABILITIES`, and the Goldfish
HEVC decoder failed with codec error `0xe`. This supplied direct evidence for one additional narrow
legacy safety correction before the atomic VOD cutover:

- the generic decoding-error branch that incorrectly sent a video-renderer failure through the
  safe-audio/PCM/audio-disabled ladder was deleted;
- a renderer failure whose video format is unsupported or exceeds device capabilities is now
  classified as deterministic and cannot schedule a same-graph retry; and
- the audio-specific fallback ladder remains available only through the existing explicit audio
  track failure classifier.

This does not add new recovery policy to `PlayerRuntimeController`; it removes invalid cross-domain
recovery that caused the observed unbounded loop. Post-fix simulator evidence showed one subtitle
fetch, one player initialization, one decoder failure, and a stable terminal playback-error view
for more than eight seconds with no rebuild or subtitle-refetch cycle. The provider connection was
released after validation.

Release remains stopped. This containment proves that the current production route terminates
correctly for the reproduced unsupported-device case; it does not certify the planned clean VOD
session cutover or real-device 4K HEVC support.
