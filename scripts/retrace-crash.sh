#!/usr/bin/env bash
# Retrace a minified Android stack trace with an archived R8 mapping file.
#
#   scripts/retrace-crash.sh mapping-tv-1.24.0+141-full-apk.txt < stack.txt
#
# The mapping is the one CI archived for that exact build (Actions artifact
# "mapping-<app>-<version>+<code>"; pick the file matching the report's flavor and
# $app_version / $app_build). Stack frames look like "at u2.a.a(SourceFile:3)".
# Uses the Android SDK cmdline-tools `retrace` (ANDROID_HOME, falling back to ANDROID_SDK_ROOT).
set -euo pipefail

if [ "$#" -ne 1 ] || [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
  echo "usage: $0 <mapping.txt> < minified-stack.txt" >&2
  exit 2
fi
mapping=$1
[ -s "$mapping" ] || { echo "error: mapping file not found or empty: $mapping" >&2; exit 1; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
retrace=""
if [ -n "$sdk" ]; then
  # newest cmdline-tools version first ("latest" sorts after numbers with -V, fine either way)
  for d in $(ls -d "$sdk"/cmdline-tools/*/ 2>/dev/null | sort -rV); do
    if [ -x "${d}bin/retrace" ]; then retrace="${d}bin/retrace"; break; fi
  done
fi
if [ -z "$retrace" ]; then
  echo "error: Android SDK cmdline-tools 'retrace' not found." >&2
  echo "       Set ANDROID_HOME and install it: sdkmanager 'cmdline-tools;latest'" >&2
  exit 1
fi
if [ -z "${JAVA_HOME:-}" ] && ! java -version >/dev/null 2>&1; then
  echo "error: no Java runtime. Set JAVA_HOME (e.g. /opt/homebrew/opt/openjdk@17); retrace is a Java tool." >&2
  exit 1
fi
if [ -t 0 ]; then
  echo "paste the minified stack, then Ctrl-D:" >&2
fi
stack=$(mktemp)
trap 'rm -f "$stack"' EXIT
cat > "$stack"
[ -s "$stack" ] || { echo "error: empty stack on stdin" >&2; exit 1; }
"$retrace" "$mapping" "$stack"
