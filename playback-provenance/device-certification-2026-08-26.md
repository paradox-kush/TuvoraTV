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

## 2026-08-27 candidate — explicit libmpv system-DNS fallback

- Git commit: `b6378bf1c`
- Debug APK: `app-full-armeabi-v7a-debug.apk`
- Debug APK SHA-256: `e12167f01595f73402d688259dc009c5993d137806c04b8757da07a091454e8a`
- Source selection: one request object from the active TV profile's device-local live recents; the
  lab displayed only a safe ordinal, title, and one-way fingerprint
- Engine order: libmpv first, affirmative release, then Media3 on the exact same request object
- Provider safety: the app was force-stopped before install and again after both runs; only Onn was
  used, and no Fire TV request was opened

### Onn dual-engine guide-profile comparison

| Fact | libmpv | Media3 |
| --- | --- | --- |
| Preflight | `SYSTEM_DNS_FALLBACK` | `ELIGIBLE` |
| DNS materialization | System resolver; no claim that app DoH was applied | Application resolver |
| Surface | `MPV_DIRECT`, 1920x808 | SurfaceView, 1920x808 |
| Video | HEVC, 3840x2160 | HEVC, 3840x2160 |
| Decoder | MediaCodec through libmpv | `c2.amlogic.hevc.decoder` |
| Session to READY | 3,558 ms | 3,215 ms |
| Session to first video frame | 3,709 ms | 3,120 ms |
| First audio | Yes | Yes |
| Measured active window | about 67 seconds | about 45 seconds |
| Final dropped-frame sample | 0 | 0 |
| Release | `RELEASED` | READY -> IDLE -> `RELEASED` |
| Result | PASS: fallback admission, open, decode, render facts, stability window, release | PASS: comparison open, decode, smooth operator-visible video, stability window, release |

### Interpretation limits

- ADB screenshots showed a black video rectangle for both engines because both runs used separately
  composed hardware video surfaces. After the run, the operator confirmed the last (Media3) video
  was playing smoothly; the libmpv row retains event-based render evidence but does not claim a new
  operator-visible confirmation.
- This is evidence that the approved libmpv system-DNS fallback can open the selected DoH-profile
  live request on Onn. It is not evidence of DoH parity in libmpv; the adapter and lab explicitly
  report the downgrade.
- No DNS, TLS, authentication, or other network result from this fallback is eligible to teach an
  engine, decoder, surface, or device compatibility preference.
- Fire TV, surface recreation for libmpv, reconnect/failover, rapid zap, and long-soak certification
  remain open.
