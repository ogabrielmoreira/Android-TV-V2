#!/usr/bin/env bash
# Troca a tela inicial entre a de fabrica (Google TV / Android TV) e a V2.
#   scripts/switch-interface.sh android-tv   -> volta para a tela de fabrica
#   scripts/switch-interface.sh nova         -> volta para a V2
set -euo pipefail
source "$(dirname "$0")/common.sh"
connect
STOCK="$(stock_launcher)"
[ -n "$STOCK" ] || { echo "Nao achei a tela inicial de fabrica nesta TV (use: export STOCK_LAUNCHER=pacote)"; exit 1; }
case "${1:-}" in
  android-tv)
    adb shell pm enable "$STOCK"
    ;;
  nova)
    adb shell pm disable-user --user 0 "$STOCK"
    set_home
    ;;
  *) echo "uso: $0 android-tv | nova"; exit 1 ;;
esac
adb shell input keyevent KEYCODE_HOME
sleep 2
echo "Tela atual: $(adb shell dumpsys window | grep mCurrentFocus | tr -d '\r')"
