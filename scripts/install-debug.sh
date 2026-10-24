#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

APK="$(dirname "$0")/../app/build/outputs/apk/debug/app-debug.apk"

if command -v su >/dev/null 2>&1 && su -c 'id -u' 2>/dev/null | grep -qx 0; then
    su -c "pm install -r '$APK'"
elif command -v termux-open >/dev/null 2>&1; then
    termux-open "$APK"
else
    echo "请手动安装: $APK"
    exit 1
fi
