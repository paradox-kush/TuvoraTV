#!/bin/bash -e

## Dependency versions
# Make sure to keep v_ndk and v_ndk_n in sync, both are listed on the NDK download page

v_sdk=11076708_latest
v_ndk=r29
v_ndk_n=29.0.14206865
v_sdk_platform=35
v_sdk_build_tools=35.0.0

v_lua=5.2.4
v_unibreak=6.1
v_harfbuzz=12.2.0
v_fribidi=1.0.16
v_freetype=2.14.1
v_mbedtls=3.6.5
v_libxml2=2.13.5

# Native revisions embedded in the published mpv-android-lib 0.1.12 AAR.
# Upstream's release script cloned moving default branches, so these pins are
# required for a repeatable Tuvora build.
v_dav1d=daef39627713a3e09873e78df65b268386cb4c20
v_ffmpeg=a7522f3fefa719be687f4627631ac6e5e481ef4c
v_libass=fadc390583f24eb5cf98f16925fd3adee50bca88
v_libplacebo=b2ea27dceb6418aabfe9121174c6dbb232942998
v_mpv=76a5eba991733f41310912c79c60f6c565a77cc9
v_gas_preprocessor=ac1836309c2e77023c228b7184485597286289d3


## Dependency tree
# I would've used a dict but putting arrays in a dict is not a thing

dep_mbedtls=()
dep_libxml2=()
dep_dav1d=()
dep_ffmpeg=(mbedtls dav1d libxml2)
dep_freetype2=()
dep_fribidi=()
dep_harfbuzz=()
dep_unibreak=()
dep_libass=(freetype2 fribidi harfbuzz unibreak)
dep_lua=()
dep_libplacebo=()
dep_mpv=(ffmpeg libass lua libplacebo)
dep_mpv_android=(mpv)


## for CI workflow

# The published 0.1.12 release did not use the CI prefix path and therefore
# built FFmpeg from the revision above rather than the n8.0 tag.
v_ci_ffmpeg=$v_ffmpeg

# filename used to uniquely identify a build prefix
ci_tarball="prefix-ndk-${v_ndk}-lua-${v_lua}-unibreak-${v_unibreak}-harfbuzz-${v_harfbuzz}-fribidi-${v_fribidi}-freetype-${v_freetype}-mbedtls-${v_mbedtls}-ffmpeg-${v_ci_ffmpeg}.tgz"
