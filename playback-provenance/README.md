# Playback engine provenance

This directory records the exact engine inputs accepted by the NuvioTV clean-slate player. The
Gradle verification tasks fail when a checked artifact or stock Media3 runtime version drifts.

## Media3 fork

- Source: `https://github.com/paradox-kush/media`
- Branch: `nuvio-engine/1.11.0`
- Revision: `891107e9f20eadef302920409830419659edb9b8`
- Upstream base: AndroidX Media3 tag `1.11.0` at `2bc207851d`
- NDK: `29.0.14206865`
- CMake: `3.22.1`

The fork adds Nuvio engine configuration, native allocator/data-path support, Dolby Vision profile-7
mapping, the allocator reset fix, and affirmative renderer-release and video-output-detach result
APIs documented by `NUVIO_ENGINE.md` in the source repository. A separate await API retains the
original release condition after timeout, so hard abort never repeats facade teardown or treats an
"already released" flag as renderer proof. The clean adapter treats a false result as a failed
ownership barrier. Do not replace these AARs with stock Media3 without separately removing those
requirements.

Build the six consumed fork AARs from the pinned source:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew --no-configuration-cache \
  :lib-common:assembleRelease \
  :lib-datasource:assembleRelease \
  :lib-datasource-okhttp:assembleRelease \
  :lib-exoplayer:assembleRelease \
  :lib-exoplayer-hls:assembleRelease \
  :lib-extractor:assembleRelease
```

Outputs are under `libraries/<module>/buildout/outputs/aar/`. A clean build at the revision above
reproduced all six checked-in AAR SHA-256 values exactly on 2026-08-26.

All Maven-resolved `androidx.media3` modules are forced and verified at `1.11.0`. This is necessary
because `io.github.peerless2012:ass-media:0.4.0` declares `media3-effect:1.8.0` transitively.

## libmpv wrapper and native engine

- Upstream base artifact: `io.github.abdallahmehiz:mpv-android-lib:0.1.12`
- Wrapper source: `https://github.com/abdallahmehiz/mpv-android`
- Wrapper tag/revision: `v0.1.12` / `96c3507c5fc8eedbf5fff458101cad794392eca7`
- Vendored source: `libmpv-android/`
- Consumed fork AAR: `app/libs/lib-mpv-release.aar`
- Consumed fork AAR SHA-256: `44747a57bef59979d32ab2b28d9b582cb05e91684d53f1bdf5f120183b380a8b`

The former `libmpv-android` entry was an orphaned gitlink with no `.gitmodules` mapping and did not
provide source ownership. It has been replaced by the exact wrapper source snapshot and a minimal
Nuvio-owned fork. The fork adds affirmative surface attach/detach and native-core destroy results,
keeps destroy idempotent before or after native initialization, releases the retained Java Surface
reference only after detach/core termination is proven, and exposes a monotonic
`presented-video-frame-count` only after a non-dropped, non-repeat VO `flip_page` completes. The mpv
core addition is reproducibly carried by
`buildscripts/patches/mpv-presented-video-frame-count.patch`; it is not inferred from decoder
output, reconfiguration, restart, or estimated frame rate. The wrapper also fixes its upstream
`getPropertyLong` bridge to return an actual Java/JNI 64-bit value rather than truncating through
`Integer`. Moving native dependencies in the
upstream release recipe are pinned in `buildscripts/include/depinfo.sh`:

| Native input | Revision embedded in the reference AAR |
| --- | --- |
| mpv | `76a5eba991733f41310912c79c60f6c565a77cc9` (`v0.41.0-174-g76a5eba99`) |
| FFmpeg | `a7522f3fefa719be687f4627631ac6e5e481ef4c` (`N-122742-ga7522f3fef`) |
| dav1d | `daef39627713a3e09873e78df65b268386cb4c20` (`1.5.3-18-gdaef3962`) |
| libass | `fadc390583f24eb5cf98f16925fd3adee50bca88` (`0.17.4-21-gfadc390`) |
| libplacebo | `b2ea27dceb6418aabfe9121174c6dbb232942998` (`v7.360.0`) |
| gas-preprocessor | `ac1836309c2e77023c228b7184485597286289d3` |

The remaining native inputs are release tarball versions declared in `depinfo.sh`. Build all four
ABIs on Linux or macOS:

```bash
cd libmpv-android/buildscripts
./download.sh
./buildall.sh --arch x86 mpv
./buildall.sh --arch x86_64 mpv
./buildall.sh --arch arm64 mpv
./buildall.sh
```

The final command builds armv7 plus the wrapper AAR at
`libmpv-android/app/build/outputs/aar/app-release.aar`.

The vendored `write_versions.sh` uses the Android NDK's `llvm-strings` and host-neutral Perl
replacement instead of Linux-only `readelf` column parsing and `sed -i` behavior. A complete macOS
build of the fork for all four ABIs with NDK `29.0.14206865` and Java 17 succeeded on 2026-08-26.
The checked-in release AAR is now the consumed and Gradle-verified artifact; the upstream Maven AAR
is no longer on the runtime classpath because it cannot provide the required release/surface/frame
proof APIs.

Static inspection of the fork AAR proves that every shipped ABI (`arm64-v8a`, `armeabi-v7a`,
`x86`, `x86_64`) contains both `vo_mediacodec_embed` and `vo=gpu` support. This proves build-time
DIRECT capability, not device/runtime eligibility; the clean adapter and Fire TV/Onn tests remain
the authority for enabling `MPV_DIRECT` or guide `MPV_RENDER`.

The clean libmpv adapter admits ordinary `SYSTEM`/`FOLLOW` requests with the exact default network
contract. It fails closed for redirect rejection, application-owned DNS, auth-bearing requests that
require cross-host stripping, non-null total-call timeout, and independent custom connect/read
timeouts because libmpv/FFmpeg cannot enforce those contracts exactly. An explicit `PRESERVE`
authorization request is admitted. Raw mpv/FFmpeg logging is disabled because native messages may
contain provider URLs; only normalized adapter facts cross the clean diagnostic boundary.

## Verification commands

```bash
./gradlew :app:verifyPlaybackEngineArtifacts
./gradlew :app:verifyMedia3RuntimeConvergence
./gradlew :app:dependencyInsight \
  --configuration fullDebugRuntimeClasspath \
  --dependency media3-effect
```

## Real-device adapter evidence

Use the [clean-player device smoke harness](device-adapter-smoke-harness.md) for sequential ONN and
Fire TV adapter runs. It enforces process absence before a device switch and writes only the closed,
secret-safe renderer/surface/state/error fact schema; it does not deploy or start playback.
