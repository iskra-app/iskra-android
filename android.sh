#!/bin/sh
# Собрать, поставить на подключённый телефон или эмулятор и запустить (docs/PROMPT.md, 17).
#
#   ./android.sh                      # fossDebug
#   ./android.sh --gms                # gmsDebug
#   ./android.sh -- --es probe verify-storage   # всё после -- уходит extras в intent
#
# Debug ставится как moscow.swag.iskra.dev и стоит рядом с релизом.
set -eu
cd "$(dirname "$0")"

flavor=foss
while [ $# -gt 0 ]; do
    case "$1" in
        --gms) flavor=gms; shift ;;
        --foss) flavor=foss; shift ;;
        --) shift; break ;;
        *) echo "android.sh: не знаю «$1»" >&2; exit 64 ;;
    esac
done

: "${ANDROID_HOME:=$(sed -n 's/^sdk.dir=//p' local.properties 2>/dev/null)}"
adb="$ANDROID_HOME/platform-tools/adb"
case "$flavor" in foss) task=installFossDebug ;; gms) task=installGmsDebug ;; esac

./gradlew "$task" --console=plain -q
"$adb" shell am force-stop moscow.swag.iskra.dev || true
"$adb" shell am start -n moscow.swag.iskra.dev/moscow.swag.iskra.MainActivity "$@"
