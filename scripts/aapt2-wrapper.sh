#!/system/bin/sh
# Termux/Android fallback for AGP's bundled aapt2 binary, which cannot run inside
# an app sandbox. Point ANDROID_AAPT2 to a Termux-built aapt2 to override the default.
# Usage: ./gradlew -Pandroid.aapt2FromMavenOverride=scripts/aapt2-wrapper.sh :app:assembleDebug
AAPT2="${ANDROID_AAPT2:-$PREFIX/bin/aapt2}"
if [ ! -x "$AAPT2" ]; then
    echo "aapt2 not found at $AAPT2; install android-sdk-tools or set ANDROID_AAPT2" >&2
    exit 1
fi
AAPT2_LIBS="${ANDROID_AAPT2_LIBS:-$PREFIX/lib}"
export LD_LIBRARY_PATH="$AAPT2_LIBS${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
exec "$AAPT2" "$@"
