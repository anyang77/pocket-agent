#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUTPUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libpocket_ssh.so"

mkdir -p "$(dirname "$OUTPUT")"

"$PREFIX/bin/clang" \
  --target=aarch64-linux-android26 \
  -fPIE -pie -O2 \
  -Wall -Wextra \
  -o "$OUTPUT" \
  "$ROOT/app/src/main/jni/pocket_ssh_wrapper.c"

chmod 0755 "$OUTPUT"
