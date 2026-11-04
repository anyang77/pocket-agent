#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-17-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
# App-private toolchains need both Android data-directory aliases recognized.
if [[ "$PREFIX" == /data/user/0/*/files/termux/usr ]]; then
  package="${PREFIX#/data/user/0/}"
  export TERMUX_APP__LEGACY_DATA_DIR="/data/data/${package%%/*}/files"
fi
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djdk.lang.Process.launchMechanism=FORK -Duser.home=$HOME -Djava.io.tmpdir=${TMPDIR:-$PREFIX/tmp}"

exec bash "$(dirname "$0")/../gradlew" \
  -Pandroid.aapt2FromMavenOverride="$PREFIX/bin/aapt2" \
  "$@"
