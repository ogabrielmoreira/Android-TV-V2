#!/usr/bin/env bash
# Funcoes comuns aos scripts. Use: export TV=192.168.1.44:5555 (ou salve o endereco no arquivo .tv na raiz).
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [ -z "${TV:-}" ] && [ -f "$ROOT/.tv" ]; then TV="$(cat "$ROOT/.tv")"; fi
if [ -z "${TV:-}" ]; then echo "Defina o IP da TV: export TV=192.168.1.X:5555"; exit 1; fi
case "$TV" in *:*) ;; *) TV="$TV:5555" ;; esac

if [ -x "$ROOT/.sdk/platform-tools/adb" ]; then ADB_BIN="$ROOT/.sdk/platform-tools/adb"
elif command -v adb >/dev/null; then ADB_BIN="$(command -v adb)"
else echo "adb nao encontrado (rode scripts/setup-sdk.sh)"; exit 1; fi

LAUNCHER_PKG=com.tvhome.v2
LAUNCHER_HOME=$LAUNCHER_PKG/.MainActivity
BUTTONS_SVC=$LAUNCHER_PKG/$LAUNCHER_PKG.RemoteButtonsService
adb() { "$ADB_BIN" -s "$TV" "$@"; }
connect() { "$ADB_BIN" connect "$TV" >/dev/null 2>&1 || true; }
prop() { adb shell getprop "$1" | tr -d '\r'; }

# Tela inicial de fabrica: a primeira destas instalada na TV (Google TV, Android TV, Android TV 5-7, Fire TV).
# Para forcar outra: export STOCK_LAUNCHER=pacote
stock_launcher() {
  if [ -n "${STOCK_LAUNCHER:-}" ]; then echo "$STOCK_LAUNCHER"; return; fi
  local p
  for p in com.google.android.apps.tv.launcherx com.google.android.tvlauncher \
           com.google.android.leanbacklauncher com.amazon.tv.launcher; do
    if adb shell pm path "$p" 2>/dev/null | grep -q package:; then echo "$p"; return; fi
  done
}

is_fire_tv() { [ "$(prop ro.product.manufacturer)" = "Amazon" ]; }

# Deixa a V2 como tela inicial. "cmd package" so existe do Android 7 em diante; no 5 e 6 basta o launcher de
# fabrica estar desativado (se aparecer "Abrir com", escolha Inicio e "Sempre").
set_home() {
  adb shell cmd package set-home-activity "$LAUNCHER_HOME" 2>/dev/null | grep -q -i success \
    || adb shell pm set-home-activity "$LAUNCHER_HOME" >/dev/null 2>&1 || true
}

# Liga o servico "Botoes do controle". Zerar e gravar de novo forca o Android a religar o servico,
# que ele desliga sempre que o launcher e reinstalado.
enable_buttons_service() {
  local cur new
  cur="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
  case "$cur" in
    null|"") new="$BUTTONS_SVC" ;;
    *"$BUTTONS_SVC"*) new="$cur" ;;
    *) new="$cur:$BUTTONS_SVC" ;;
  esac
  adb shell settings put secure enabled_accessibility_services '""'
  sleep 1
  adb shell settings put secure enabled_accessibility_services "$new"
  adb shell settings put secure accessibility_enabled 1
}
