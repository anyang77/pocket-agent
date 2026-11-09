#!/usr/bin/env bash
# One explicitly requested, potentially billed live generation using the real client.
# Build first: bash scripts/build-termux.sh :app:assembleDebug
# Usage: bash scripts/test-image-generation.sh MODEL [OUTPUT_DIRECTORY]
# Asynchronous launcher: exit 0 means started, NOT successful generation.
# Inspect the printed result.log for IMAGE_PROBE_SUCCESS / IMAGE_PROBE_FAILED.
# Never automatically rerun after IMAGE_PROBE_START (may already have been billed).
set -euo pipefail
cd "$(dirname "$0")/.."
MODEL="${1:?Supply the explicitly selected image model}"
OUTPUT="${2:-$PWD/generated-images}"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
CLASSES=app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar
KOTLIN=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" -name '*.jar' | head -1)
COROUTINES=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' | sort | tail -1)
OKHTTP=$(find "$HOME/.gradle/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp" -name '*.jar' | sort | tail -1)
D=$(mktemp -d "$TMPDIR/image-probe.XXXXXX")
export IMAGE_PROBE_DIR="$D"
mkdir "$D/classes" "$D/dex"
javac -source 17 -target 17 -cp "$CLASSES:$SDK/platforms/android-35/android.jar:$KOTLIN:$COROUTINES:$OKHTTP" -d "$D/classes" scripts/ImageGenerationSmoke.java
python - <<'PY'
import os, pathlib, zipfile
d = pathlib.Path(os.environ['IMAGE_PROBE_DIR'])
with zipfile.ZipFile(d / 'harness.jar', 'w') as z:
    for p in (d / 'classes').rglob('*.class'):
        z.write(p, p.relative_to(d / 'classes'))
PY
java -cp "$SDK/build-tools/${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --classpath "$CLASSES" --classpath "$KOTLIN" --classpath "$COROUTINES" --classpath "$OKHTTP" --output "$D/dex" "$D/harness.jar"
python - <<'PY'
import os, pathlib, zipfile
d = pathlib.Path(os.environ['IMAGE_PROBE_DIR'])
with zipfile.ZipFile(d / 'harness-dex.jar', 'w') as z:
    z.write(d / 'dex/classes.dex', 'classes.dex')
PY
cp app/build/outputs/apk/debug/app-debug.apk "$D/app-debug.apk"
chmod 444 "$D/harness-dex.jar" "$D/app-debug.apk"
echo "Probe artifacts: $D"
nohup env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH="$D/harness-dex.jar:$D/app-debug.apk" /system/bin/app_process /system/bin ImageGenerationSmoke "$MODEL" "$OUTPUT" > "$D/result.log" 2>&1 < /dev/null &
echo "$!" > "$D/pid"
sleep 3
cat "$D/result.log"
