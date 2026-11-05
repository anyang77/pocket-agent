#!/usr/bin/env bash
# Build first: bash scripts/build-termux.sh :app:assembleRelease :app:testDebugUnitTest
# Optional $1: private history BACKUP fixture. Never pass a live history directory.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
TOOLS="$SDK/build-tools/34.0.0"
CLASSES=app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar
KOTLIN=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib" -name '*.jar' | head -1)
D=$(mktemp -d "${TMPDIR:-/tmp}/history-store-smoke.XXXXXX")
export HISTORY_SMOKE_DIR="$D"
mkdir -p "$D/classes" "$D/dex"
javac -source 17 -target 17 -cp "$CLASSES:$SDK/platforms/android-35/android.jar:$KOTLIN" -d "$D/classes" scripts/HistoryStoreSmoke.java
python3 - <<'PY'
import os,zipfile
from pathlib import Path
d=Path(os.environ['HISTORY_SMOKE_DIR'])
with zipfile.ZipFile(d/'harness.jar','w') as z:
    for p in (d/'classes').rglob('*.class'): z.write(p,str(p.relative_to(d/'classes')))
PY
java -cp "$TOOLS/lib/d8.jar" com.android.tools.r8.D8 --min-api 26 --lib "$SDK/platforms/android-35/android.jar" --classpath "$CLASSES" --classpath "$KOTLIN" --output "$D/dex" "$D/harness.jar"
python3 - <<'PY'
import os,zipfile
from pathlib import Path
d=Path(os.environ['HISTORY_SMOKE_DIR'])
with zipfile.ZipFile(d/'harness-dex.jar','w') as z: z.write(d/'dex/classes.dex','classes.dex')
PY
cp app/build/outputs/apk/release/app-release.apk "$D/app-release.apk"
chmod 444 "$D/harness-dex.jar" "$D/app-release.apk"
env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH="$D/harness-dex.jar:$D/app-release.apk" /system/bin/app_process /system/bin HistoryStoreSmoke "$D/data" "$@"
echo "Private test artifacts: $D"
