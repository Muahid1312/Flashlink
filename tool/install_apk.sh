#!/bin/sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
apk=${1:-"$project_root/build/artifacts/flashlink-debug.apk"}
[ -s "$apk" ] || { echo 'No nonempty APK at the selected path. Build and verify it first with tool/build_apk.sh.' >&2; exit 1; }
# -d requires exactly one attached USB Android device. Connect each test phone in turn.
exec "${ADB:-adb}" -d install -r "$apk"
