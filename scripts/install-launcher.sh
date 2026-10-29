#!/usr/bin/env bash
# Instala o launcher (launcher/build/tvhome.apk) e o deixa como tela inicial:
#  1. confere o Android da TV (5.0 ou mais novo)
#  2. instala/atualiza o APK
#  3. desativa (nao apaga) a tela inicial de fabrica, que tem prioridade de sistema no botao Home
#  4. liga o servico "Botoes do controle" (so o botao Apps -> Todos os apps; os outros botoes nao mudam)
#  5. libera o acesso a uso, para o menu de baixo aprender os apps abertos (inclusive pelo controle)
#  6. aperta Home
# No Fire TV a Amazon nao deixa trocar a tela inicial: o launcher fica instalado como app.
# Desfazer: scripts/switch-interface.sh android-tv
set -euo pipefail
source "$(dirname "$0")/common.sh"
APK="${1:-$ROOT/launcher/build/tvhome.apk}"
[ -f "$APK" ] || { echo "APK nao encontrado: $APK (rode launcher/build.sh)"; exit 1; }
connect

SDK="$(prop ro.build.version.sdk)"
echo "TV: $(prop ro.product.manufacturer) $(prop ro.product.model), Android $(prop ro.build.version.release) (API $SDK)"
if [ "${SDK:-0}" -lt 21 ]; then
  echo "Esta TV tem Android anterior ao 5.0. A V2 precisa do Android 5.0 ou mais novo."; exit 1
fi

if ! OUT="$(adb install -r "$APK" 2>&1)" || echo "$OUT" | grep -q Failure; then
  # APK assinado com outra chave (ex.: compilado em outro computador): so da para instalar do zero.
  if echo "$OUT" | grep -q -E "UPDATE_INCOMPATIBLE|signatures do not match"; then
    echo "Assinatura diferente da versao instalada: reinstalando do zero."
    echo "Se tinha papeis de parede seus, rode scripts/push-wallpapers.sh de novo (eles sao apagados)."
    adb uninstall "$LAUNCHER_PKG"
    adb install "$APK"
  else
    echo "$OUT"; exit 1
  fi
fi
adb shell appops set "$LAUNCHER_PKG" GET_USAGE_STATS allow >/dev/null 2>&1 || true

if is_fire_tv; then
  echo
  echo "Fire TV: a Amazon nao deixa trocar a tela inicial, entao a V2 fica instalada como app."
  echo "Abra pelo menu de apps da Fire TV (\"Inicio\"). O botao Home do controle continua indo para a tela da Amazon."
  adb shell am start -n "$LAUNCHER_HOME" >/dev/null
  exit 0
fi

STOCK="$(stock_launcher)"
if [ -n "$STOCK" ]; then
  echo "Tela inicial de fabrica: $STOCK (desativada, nao apagada)"
  adb shell pm disable-user --user 0 "$STOCK" >/dev/null || true
fi
set_home
enable_buttons_service
adb shell am force-stop com.android.tv.settings >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_HOME
sleep 3
echo "Tela atual: $(adb shell dumpsys window | grep mCurrentFocus | tr -d '\r')"
