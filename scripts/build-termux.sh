#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-17-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

exec "$(dirname "$0")/../gradlew" \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
  "$@"
