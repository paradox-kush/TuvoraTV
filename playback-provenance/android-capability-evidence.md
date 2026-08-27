# Android capability and quirk evidence

The clean Android capability lane re-reads framework observations for every snapshot. Codec
capabilities include hardware/software/vendor classification, secure playback, profile/level,
maximum-instance hints, independent size/rate ranges, canonical performance-point descriptors, and
coupled `areSizeAndRateSupported(width, height, fps)` probes. Independent maximum width, height, and
global frame-rate bounds are never combined into a fictitious format. The current core model cannot
carry coupled evidence, so its `maxDimensions` and `maxFrameRate` stay null (unknown); Android's
coupled evidence remains in `AndroidRuntimeCapabilitySnapshot.decoderFacts`.

The global concurrent decoder budget is the minimum known hardware-decoder hint. A missing hardware
hint collapses the budget to one, and software codecs never inflate it. Display modes/HDR,
memory/thermal state, API level, and surface facts are collected independently.

Audio route evidence comes only from an active engine's `AudioRouting`/`AudioTrack` routed device.
Without that input the route is `UNKNOWN`; the collector does not rank connected output candidates
and call the winner active. Basic `SurfaceView`/`TextureView` class availability is separate from
GPU/EGL, secure-surface, and protected-GPU proof. All three proof states remain `UNPROBED` until a
real engine/surface probe supplies evidence. GLES 2, free memory, or a secure decoder is not proof.
Offload likewise stays false until a concrete audio format and attributes are tested.

`AndroidPlaybackQuirkRegistry` version 1 carries one device override: Amazon `AFTKM` guide-embedded
playback uses `TEXTURE_VIEW`. This exact model was physically verified in
`research/livetv-freeze-rca-2026-08-26.md`, section F1. The earlier family heuristic is intentionally
not copied: no other Amazon/MediaTek model is admitted without device evidence. The record becomes
due for revalidation on 2027-02-26 and expires on 2027-08-26.

Core integration gaps intentionally left for the core owner:

- `PlaybackRequirementsInput` does not yet accept `RuntimeCapabilities` or compatibility history,
  so a pure requirements resolver cannot consume the collected facts.
- `RuntimeCapabilities` currently names only observation `snapshotVersion`; the Android snapshot
  therefore exposes a separate versioned SHA-256 stable capability fingerprint and a ready-to-store
  core `CompatibilityRuntimeFingerprint`. They include stable device, firmware, API,
  codec/profile/instance, HDR, surface-proof, and quirk facts while excluding route, memory, thermal
  state, timestamp, and observation sequence. Compatibility history uses the stable fingerprint,
  never the refresh sequence.
- `RuntimeCapabilities.verifiedQuirkIds` preserves proof identity, but the core requirements model
  has no typed embedded-surface constraint. The Android collector therefore returns the narrow typed
  override separately and does not falsify global `SurfaceCapabilities` to force a guide decision.

## Real-device smoke checkpoints

On 2026-08-26, `AndroidRuntimeCapabilityDeviceTest` ran sequentially through AndroidX
instrumentation on both authorized devices. The ONN process was stopped and confirmed absent before
switching to Fire TV; neither debug process remained after its probe.

- ONN `192.168.1.236:5555`: `onn/onn. 4K Streaming Box`, Android 14/API 34, 19 decoder entries,
  3840x2160 current display, HDR10 + HLG, stable capability fingerprint prefix `c2db6faf12f5`,
  `UNKNOWN` audio-route evidence, and no applied quirks.
- Fire TV `192.168.1.225:5555`: `Amazon/AFTKM`, API 30, 30 decoder entries, 1920x1080 current
  display, stable capability fingerprint prefix `88b35ea59ae6`, `UNKNOWN` audio-route evidence, and
  the exact `amazon-aftkm-embedded-surface-texture-v1` quirk positively applied.

These are API/fact smoke tests, not rendering proof. The test requires a complete device identity
profile, cross-checks captured coupled codec probes against fresh framework queries, expects
`UNKNOWN` audio without routed evidence, and verifies unprobed GPU/secure facts remain false. Actual
decode, EGL, secure-output, active audio routing, and surface-lifecycle proof remain WP4/WP5/WP8
playback fixture gates.
