# Clean adapter video frame-rate facts — 2026-08-27

## Boundary

This change propagates factual selected-video frame rate from the two clean TV adapters through
`PlaybackEvent.VideoFrameRateChanged`. It does not choose a display mode, apply an AFR setting,
change session policy, or infer a rate from playback cadence. The generation is attached only by
the existing generation-bound engine event bridge.

## Media3 evidence

`AndroidMedia3Backend` reads `Format.frameRate` only from
`AnalyticsListener.onVideoInputFormatChanged`. The MIME-type fact is still emitted independently.
`C.RATE_UNSET`, non-finite values, and rates outside the core's inclusive 10–120 fps fact range are
ignored. Focused primitive tests cover a valid fractional rate, both inclusive boundaries, unset,
NaN, infinity, zero, and out-of-range values; the engine test proves generation propagation.

## libmpv evidence

`AndroidMpvBackend` parses `demux-fps` only from the selected video entry in the already-observed
`track-list` node. Repeated identical values are suppressed. `estimated-vf-fps` is deliberately
ignored because it is renderer/runtime cadence rather than stable content metadata. The existing
file-loaded synchronous track fallback remains for track availability but cannot publish a frame
rate. Focused parser/backend tests cover selected and unselected entries, a valid fractional rate,
missing/estimated-only metadata, non-finite values, and both out-of-range sides; the engine test
proves generation propagation.

## Platform scope

The clean Media3/libmpv adapter packages exist only in NuvioTV. NuvioMobile, NuvioDesktop,
`nuvio-backend`, and `nuvio-web` have no twin clean adapter or shared event bridge, so no source is
portable there in this work package.
