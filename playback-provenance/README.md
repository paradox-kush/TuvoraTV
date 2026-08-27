# Playback engine provenance

This directory records the exact engine inputs accepted by the NuvioTV clean-slate player. The
Gradle verification tasks fail when a checked artifact or stock Media3 runtime version drifts.

## Media3 fork

- Source: `https://github.com/paradox-kush/media`
- Branch: `nuvio-engine/1.11.0`
- Revision: `350b29df6d31a698eb382e55ddb0952fe6afea99`
- Upstream base: AndroidX Media3 tag `1.11.0` at `2bc207851d`
- NDK: `29.0.14206865`
- CMake: `3.22.1`

The fork adds Nuvio engine configuration, native allocator/data-path support, Dolby Vision profile-7
mapping, and the allocator reset fix documented by `NUVIO_ENGINE.md` in the source repository. Do
not replace these AARs with stock Media3 without separately removing those requirements.

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

- Published artifact: `io.github.abdallahmehiz:mpv-android-lib:0.1.12`
- Wrapper source: `https://github.com/abdallahmehiz/mpv-android`
- Wrapper tag/revision: `v0.1.12` / `96c3507c5fc8eedbf5fff458101cad794392eca7`
- Vendored source: `libmpv-android/`
- Reference AAR SHA-256: `bb1a007c545cc7ac3304293ae79866b5361a48449ee7648c4030d5355869effc`

The former `libmpv-android` entry was an orphaned gitlink with no `.gitmodules` mapping and did not
provide source ownership. It has been replaced by the exact wrapper source snapshot. Moving native
dependencies in the upstream release recipe are pinned in `buildscripts/include/depinfo.sh`:

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
build with NDK `29.0.14206865` and Java 17 succeeded on 2026-08-26. Its release AAR SHA-256 was
`4a5b666982e77820bdb6e2a0dded42f5fab733bd0ab4a70d7f4ffdf2ee6f9561`.

That locally rebuilt AAR is source-equivalent but intentionally not recorded as byte-identical to
the published artifact. Both archives contain the same 56 paths and all four ABIs expose the same
mpv revision, `mediacodec_embed`, and GPU/GPU-next paths. The published archive embeds its original
per-ABI February 2026 compile times and unstripped NDK `libc++_shared.so` debug sections; the local
Android Gradle release build embeds its own compile times and strips those debug sections. These
non-runtime differences change whole-file hashes. Release consumption therefore remains pinned to
the published AAR hash above, while the vendored recipe proves source ownership and feature parity.

Static inspection of the published AAR proves that every shipped ABI (`arm64-v8a`, `armeabi-v7a`,
`x86`, `x86_64`) contains both `vo_mediacodec_embed` and `vo=gpu` support. This proves build-time
DIRECT capability, not device/runtime eligibility; the clean adapter and Fire TV/Onn tests remain
the authority for enabling `MPV_DIRECT` or guide `MPV_RENDER`.

## Verification commands

```bash
./gradlew :app:verifyPlaybackEngineArtifacts
./gradlew :app:verifyMedia3RuntimeConvergence
./gradlew :app:dependencyInsight \
  --configuration fullDebugRuntimeClasspath \
  --dependency media3-effect
```
