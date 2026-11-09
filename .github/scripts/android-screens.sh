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
# Primeira abertura, sem conta: tela de entrada (login com Fotto). O modo demonstração entra sem conta.
start
shot 0-login 8
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
# Modais (LumoSheet): menu, saúde do fluxo, eventos e tour.
start --es lumoSheet menu
shot 7-menu 8
start --es lumoSheet saude
shot 8-saude 8
start --es lumoSheet eventos
shot 9-eventos 8
start --es lumoSheet tour
shot 10-tour 8
# Tablet deitado (1280x800 dp): cartão centralizado e topo em largura grande.
adb shell wm size 2560x1600
adb shell wm density 320
start
shot 11-tablet-ao-vivo 10
start --es lumoSheet menu
shot 12-tablet-menu 8
adb shell wm size reset
adb shell wm density reset
ls -l screenshots
# Revelação na GPU: autoteste contra o motor Java e tempos de 12 MP (no emulador a GPU é por software).
adb logcat -c
start --ez lumoDemo true --ez lumoGpuTest true
for i in $(seq 1 90); do
  if adb logcat -d -s LUMO-GPU | grep -q "fim do teste da GPU"; then break; fi
  sleep 5
done
sleep 3
adb logcat -d -s LUMO-GPU | tee screenshots/gpu.txt
if grep -q "DIVERGE" screenshots/gpu.txt; then cp screenshots/gpu.txt screenshots/gpu-diverge.txt; fi
echo "===== LOGCAT (erros do app) ====="
adb logcat -d -b crash | tail -120
# Se o app fechou em algum momento, guarda o log; o workflow falha depois de publicar as capturas.
adb logcat -d -b crash | grep -A40 "Process: com.hisho.tether" > screenshots/crash.txt || true
adb logcat -d | grep -E "FATAL|AndroidRuntime|LUMO|hisho" | tail -120
