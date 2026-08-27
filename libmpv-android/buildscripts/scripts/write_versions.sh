#!/bin/bash
set -euo pipefail

# Credit goes to jmir1. Keep this script host-portable: the wrapper is supported on both Linux and
# macOS, and Android NDK tools are the stable authority for inspecting Android object files.
ndk_suffix="${1:-}"
mpv_build_dir="buildscripts/deps/mpv/_build${ndk_suffix}"
libplacebo_build_dir="buildscripts/deps/libplacebo/_build${ndk_suffix}"
versions_file="app/src/main/java/is/xyz/mpv/Utils.kt"

MPV_VERSION=$(awk -F '"' '/#define VERSION/ { print $2; exit }' "${mpv_build_dir}/common/version.h")
LIBPLACEBO_VERSION=$(awk -F '"' '/#define BUILD_VERSION/ { print $2; exit }' "${libplacebo_build_dir}/src/version.h")
FFMPEG_VERSION=$(git -C buildscripts/deps/ffmpeg rev-parse --short HEAD)

LLVM_STRINGS=$(command -v llvm-strings || true)
if [[ -z "${LLVM_STRINGS}" ]]; then
    echo "llvm-strings was not found; source builds must run with the Android NDK toolchain on PATH" >&2
    exit 1
fi

# mpv compiles BUILDDATE into common_version.c.o. Reading the object preserves the exact value
# shown by the native engine without depending on host-specific readelf column layouts.
DATE=$("${LLVM_STRINGS}" "${mpv_build_dir}/libmpv.so.p/common_version.c.o" |
    awk '/^(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) [ 0-9][0-9] [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2}$/ { print; exit }')
if [[ -z "${DATE}" ]]; then
    echo "Unable to extract the mpv build date from common_version.c.o" >&2
    exit 1
fi

export MPV_VERSION LIBPLACEBO_VERSION FFMPEG_VERSION DATE
perl -pi -e 's/%MPV_VERSION%/$ENV{MPV_VERSION}/g; s/%LIBPLACEBO_VERSION%/$ENV{LIBPLACEBO_VERSION}/g; s/%FFMPEG_VERSION%/$ENV{FFMPEG_VERSION}/g; s/%DATE%/$ENV{DATE}/g' "${versions_file}"
