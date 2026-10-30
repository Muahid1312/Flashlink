#!/bin/sh
set -eu
project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
mode=${1:-debug}
case "$mode" in debug|release|preview) ;; *) echo 'Usage: tool/build_apk.sh [debug|release|preview]' >&2; exit 2 ;; esac
cd "$project_root"
artifact="$project_root/build/artifacts/flashlink-$mode.apk"
# Never leave a previous build at the named output after this attempt fails.
rm -f "$artifact"
if [ "$mode" = release ] && [ ! -f android/key.properties ]; then
  echo 'Release signing is not configured. Supply your existing keystore through ignored android/key.properties, or run tool/build_apk.sh debug.' >&2
  exit 1
fi
build_mode=$mode
if [ "$mode" = preview ]; then build_mode=release; fi
source_apk="$project_root/build/app/outputs/flutter-apk/app-$build_mode.apk"
rm -f "$source_apk"
if [ "$mode" = preview ]; then
  ORG_GRADLE_PROJECT_flashlinkPreview=true "${FLUTTER:-flutter}" build apk --release --dart-define=FLASHLINK_TEST_PREVIEW=true
else
  "${FLUTTER:-flutter}" build apk "--$mode"
fi
[ -s "$source_apk" ] || { echo 'Flutter did not produce a nonempty APK.' >&2; exit 1; }
# Verify installable signing before publishing a named artifact. No keys are invented.
apksigner_path=${APKSIGNER:-}
if [ -z "$apksigner_path" ]; then
  apksigner_path=$(python3 - <<'PY'
import os,pathlib,re
sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
if not sdk:
 for line in pathlib.Path('android/local.properties').read_text().splitlines():
  if line.startswith('sdk.dir='):sdk=line.split('=',1)[1].replace('\\:',':').replace('\\\\','\\')
if not sdk:raise SystemExit('Android SDK path unavailable; set ANDROID_HOME or APKSIGNER')
choices=[p for p in pathlib.Path(sdk,'build-tools').glob('*/apksigner') if re.fullmatch(r'\d+(\.\d+)*',p.parent.name)]
if not choices:raise SystemExit('apksigner is unavailable; install Android build-tools')
print(max(choices,key=lambda p:tuple(map(int,p.parent.name.split('.')))))
PY
  )
fi
"$apksigner_path" verify "$source_apk"
python3 tool/verify_apk.py "$source_apk" "$mode" "$(dirname -- "$apksigner_path")/aapt2"
mkdir -p build/artifacts
cp "$source_apk" "$artifact"
[ -s "$artifact" ] || exit 1
printf 'Verified %s APK: %s\n' "$mode" "$artifact"
if [ "$mode" = preview ]; then
  echo 'Test-installable release/AOT preview; Android test signing only. NOT a production-signed release.'
fi
