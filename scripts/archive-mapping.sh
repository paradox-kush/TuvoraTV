#!/usr/bin/env bash
# Copy a freshly built R8 mapping.txt to a versioned name for archiving by CI.
#
# Android overwrites build/outputs/mapping/<variant>/mapping.txt on every build, and the APK and
# AAB builds of the same variant can run R8 separately, so CI calls this right after EACH Gradle
# invocation. Fails (non-zero) when the mapping is missing or empty so a release can never ship
# a minified build whose stacks cannot be retraced.
#
# usage: scripts/archive-mapping.sh <module-dir> <app> <versionName> <versionCode> <variant> <label>
#   e.g. scripts/archive-mapping.sh app tv 1.25.0 150 fullRelease full-apk
#        -> mappings/mapping-tv-1.25.0+150-full-apk.txt
set -euo pipefail

if [ "$#" -ne 6 ]; then
  echo "usage: $0 <module-dir> <app> <versionName> <versionCode> <variant> <label>" >&2
  exit 2
fi
module=$1 app=$2 version=$3 code=$4 variant=$5 label=$6

src="$module/build/outputs/mapping/$variant/mapping.txt"
out_dir="${MAPPING_OUT_DIR:-mappings}"
dest="$out_dir/mapping-$app-$version+$code-$label.txt"

if [ ! -s "$src" ]; then
  echo "::error::R8 mapping missing or empty: $src (release stacks would be unreadable)" >&2
  exit 1
fi
mkdir -p "$out_dir"
cp "$src" "$dest"
echo "archived $src -> $dest ($(wc -c < "$dest") bytes)"
