#!/usr/bin/env bash
# Envia seus papeis de parede para o launcher, sem recompilar.
# Uso: scripts/push-wallpapers.sh pasta-com-fotos
#  - fotos .jpg/.png da pasta substituem as 12 fotos de natureza (trocam a cada 10 min)
set -euo pipefail
source "$(dirname "$0")/common.sh"
[ -n "${1:-}" ] || { echo "uso: $0 pasta-com-fotos"; exit 1; }
DIR=/sdcard/Android/data/$LAUNCHER_PKG/files
connect
adb shell mkdir -p "$DIR/wallpapers"
adb shell "rm -f $DIR/wallpapers/*"
for f in "$1"/*.jpg "$1"/*.jpeg "$1"/*.png; do
  [ -f "$f" ] && adb push "$f" "$DIR/wallpapers/"
done
adb shell am force-stop $LAUNCHER_PKG
adb shell input keyevent KEYCODE_HOME
echo "Pronto. Para voltar as fotos de natureza: adb shell rm -r $DIR/wallpapers"
