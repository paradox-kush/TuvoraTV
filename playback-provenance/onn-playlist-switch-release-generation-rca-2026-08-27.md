# ONN playlist/channel switch release-generation RCA — 2026-08-27

## Release disposition

The v1.5.9 release was stopped before any tag or GitHub release was created. The correction now has
partial real-device certification on ONN hardware; fullscreen promotion/collapse and a sustained
zap matrix remain open, so the release is still blocked.

## Observed ONN behavior

- Switching a Live TV playlist and selecting another channel left the guide at
  `PreviewUnavailable(GUIDE_RESOURCE_RESTRICTION)`.
- The guide retained a decoded image, but two sequential captures of the preview viewport were
  pixel-identical. It was a frozen surface frame, not successful playback.
- The first attempt had created an Amlogic MediaCodec decoder. Later channel selection changed the
  guide identity but did not allocate another decoder.
- An earlier contained host failure showed that session release returned while the surface lease
  was still attached (`CleanLivePlaybackHost.kt:110`).

## Root cause

`PlaybackAction.ReleaseActiveWork` belongs to the replacement request, so its `generation` is the
new generation. The active adapter still belongs to `activeGraphGeneration`, the old generation.
`PlaybackSession.releaseAdapterUntilComplete` received both values but called
`engine.release(generation)` and `engine.hardAbort(generation)` with the new value.

For a generation 1 → 2 channel or account replacement, Media3 and libmpv therefore received
`release(2)` while still owning generation 1. Both adapters correctly rejected the call as stale.
The session then failed closed with `RESOURCE_RELEASE_FAILED`, retained the decoder/surface, and
never started the new channel. The guide's resource-restriction label was the terminal consequence,
not evidence that the selected channel was intrinsically too large for the device.

## Corrections

1. Replacement release now calls both graceful release and hard abort with
   `engineGeneration ?: generation`. Diagnostic and intentional-terminal suppression still carry
   both generations.
2. Public `PlaybackSession.release()` subscribes before dispatch and waits for a terminal emission
   produced after its own release command. A pre-existing `FAILED` snapshot can no longer let the
   session cancel its actor before the adapter barrier runs.
3. `XtreamLiveGuideViewModel` now gives each account an immutable commit token, cancels superseded
   category/channel work, and fences every category/channel/lineup commit. A slow old provider
   response cannot republish its lineup after the selected account changes.

## Regression contracts

- A generation-2 replacement must release adapter generation 1 before starting generation 2.
- Release from a failed state must run a fresh successful barrier and end with no active provider
  connection.
- Account A tokens must be rejected after A → B and rapid A → B → C switches; same-account option
  updates retain the current account epoch.
- A published lineup must match both the active account token and the stable content-id account
  prefix before the guide may expose its channels.

## Follow-up ONN hardware validation — 2026-08-27

- Device: `YOC`, `onn. 4K Streaming Box`, Android 14 / API 34, 32-bit `armeabi-v7a`, Amlogic
  hardware codec stack.
- The current working-tree debug build was installed without clearing profile or IPTV account data.
- A saved-provider switch from `onnipsite.site` to `bigzbuae.uk` committed the new provider and
  exposed its independent Live TV lineup. The first provider's selected Bulgarian channel ended in
  `NO_ELIGIBLE_GRAPH`; that channel was not used as positive playback evidence.
- On the second provider, Media3 initialized both Amlogic HEVC and AVC hardware decoders. The
  standard ADB screenshot and screen-record paths showed a black rectangle because the ONN placed
  the non-secure YUV SurfaceView in a hardware-composer `DEVICE` plane; this was a capture artifact,
  not sufficient playback evidence by itself.
- SurfaceFlinger reported a live non-secure buffer, repeated presentation timestamps, and a 23.98 Hz
  exact-or-multiple layer rate. A temporary debug-only PixelCopy probe then copied the SurfaceView
  itself and produced two visibly different live frames. The probe was removed after capture.
- A controlled adjacent-channel switch recorded generation 2
  `RELEASE_BARRIER_STARTED -> RELEASE_BARRIER_COMPLETED -> REQUEST_RESOLUTION_STARTED ->
  REQUEST_RESOLVED -> GRAPH_SELECTED(MEDIA3)`. Two direct post-switch SurfaceView captures taken
  later show different frames from the replacement channel, confirming that the old adapter was
  released and the new decoder/surface path continued presenting rather than retaining a frozen
  frame.
- Direct evidence:
  `onn-live-pixelcopy-a-2026-08-27.png`, `onn-live-pixelcopy-b-2026-08-27.png`,
  `onn-live-switch-frame-a-2026-08-27.png`, and `onn-live-switch-frame-b-2026-08-27.png`.
- After collecting the evidence, the temporary capture source was removed, a clean
  `armeabi-v7a` debug APK was rebuilt and reinstalled, and the app resumed successfully. Package
  inspection returned `No receivers found` for the capture action, and the temporary
  instrumentation package and staged APKs were removed from the device.

## Remaining certification

ONN initial playback, saved-provider replacement, and one direct channel change now have positive
hardware evidence. Fullscreen promotion/collapse, repeated zapping across mixed codecs, and normal
process exit/re-entry still require certification. Fire TV was unavailable and is outside this
incident's validation scope. The release remains blocked.
