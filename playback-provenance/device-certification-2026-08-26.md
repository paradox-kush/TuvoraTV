# Clean playback device certification — 2026-08-26

This is an append-only record of measured clean-player device runs. It contains no provider URL,
host, account, playlist, channel identifier, header, cookie, or credential. A row is evidence for the
exact candidate named below; it is not a substitute for the complete WP8 or post-cutover matrix.

## Candidate

- Git commit: `7369e4d6bde1b79160f20f3f873ca7665463a644`
- Debug APK: `app-full-armeabi-v7a-debug.apk`
- Debug APK SHA-256: `120603a24542bd81506848c2ffe34ddf4fe50c7f3a1510f3ef16845fdba6338f`
- Source selection: signed-in debug app, active Tuvora TV profile, selected IPTV playlist, newest
  device-local live recent
- Provider safety: the normal player was exited and `com.tuvora.tv.debug` was force-stopped before
  the lab opened; the lab was affirmatively released and force-stopped before leaving the device
- Test order: Onn first. Fire TV was not active during this provider request.

## Onn 4K Streaming Box — clean Media3 guide profile

| Fact | Observation |
| --- | --- |
| Device | onn `onn. 4K Streaming Box` |
| SoC | Amlogic `AMLS905Y4` (`ro.hardware=amlogic`) |
| Android API | 34 |
| ABI artifact | `armeabi-v7a` debug split |
| Clean graph | Media3, `GUIDE`, SurfaceView, generation 2 |
| Video | HEVC, 3840x2160 input |
| Decoder | `c2.amlogic.hevc.decoder` |
| Session to rendered first frame | 1,978 ms |
| Sustained visible playback | about 84 seconds; operator confirmed clear moving video |
| Final dropped-frame sample | 0 |
| Surface recreation | invalid -> valid on the same generation; 3840x2160 video fact returned in 72 ms |
| Provider/backend reopen on recreation | none observed; session generation remained 2 |
| Release | READY -> IDLE -> RELEASED in about 104 ms |
| Result | PASS for startup, visible video, same-session Surface recreation, and affirmative release |

### Interpretation limits

- `adb screencap` produced a black video region because the SurfaceView layer is composed separately;
  the on-device picture was visually confirmed by the operator and the adapter emitted a rendered
  first-frame fact.
- This run used a single raw/single-rendition-looking 4K HEVC result in guide profile. It correctly
  did not pretend that a smaller guide Surface reduced source resolution, bandwidth, or decode cost.
- This is not yet evidence for adaptive preview representation selection, libmpv, fallback, live
  reconnect, network interruption, rapid zap, long soak, Fire TV, or post-cutover behavior.

## Fire TV setup inventory — no playback opened

| Fact | Observation |
| --- | --- |
| Device | Amazon `AFTKM` |
| SoC / hardware | MediaTek `mt8696` |
| Android API | 30 |
| ABI | `armeabi-v7a, armeabi` |
| Debug package before certification | Not installed |
| Provider state | No Fire TV provider request opened during this inventory |

The Fire TV run remains pending the dual-engine debug APK, debug-profile sign-in, and explicit
selection of a playlist distinct from the one used on Onn.
