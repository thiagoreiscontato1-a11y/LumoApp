#!/bin/bash
# Capturas do app Android no emulador (modo demonstração). Executado pelo workflow Lumo Android.
set -x
mkdir -p screenshots
adb install -r Lumo.apk
adb shell pm grant com.hisho.tether android.permission.POST_NOTIFICATIONS || true
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
start() { adb shell am force-stop com.hisho.tether; adb shell am start -n com.hisho.tether/.MainActivity "$@"; }
shot() { sleep "$2"; adb exec-out screencap -p > "screenshots/$1.png"; }
start --ez lumoDemo true
shot 1-ao-vivo-processando 25
shot 2-ao-vivo 50
start --ei lumoPage 1
shot 3-galeria 12
start --ei lumoPage 2
shot 4-revisao 10
start --ez lumoViewer true
shot 5-visualizador 12
start --ei lumoPage 3
shot 6-entrega 10
ls -l screenshots
