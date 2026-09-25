#!/usr/bin/env bash
# Utilitários de desenvolvimento/validação do Noc (Git Bash no Windows).
#   tools/dev.sh host        reinicia o Companion headless (dados em .devdata, auto-aprova código)
#   tools/dev.sh qr          gera um QR novo e pareia o emulador pelo deep link
#   tools/dev.sh code        gera um código de pareamento e o imprime
#   tools/dev.sh shot NAME   captura a tela do emulador em shots/NAME.png
#   tools/dev.sh install     compila e instala o APK de debug
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$ROOT/.devdata"
ADB=C:/Android/sdk/platform-tools/adb.exe
PKG="${PKG:-com.noc.app}"   # release por padrão; PKG=com.noc.app.debug para o debug
T="$(cygpath -w "$ROOT/tools")"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.20.101-hotspot"

cmd_file() { echo "$1" > "$DATA/devhost-cmd.txt"; sleep 1.5; cat "$DATA/devhost-pair.txt"; }

case "$1" in
  host)
    powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/tools/devhost.ps1")" ;;
  qr)
    URI=$(cmd_file qr | head -1)
    $ADB shell "am start -a android.intent.action.VIEW -d '$URI' $PKG" ;;
  code)
    cmd_file code ;;
  release)
    (cd "$ROOT/android" && ./gradlew.bat :app:assembleRelease --console=plain -q 2>&1 | grep -E "^e: |FAIL" || true)
    $ADB install -r "$ROOT/android/app/build/outputs/apk/release/app-release.apk" | tail -1 ;;
  pair-code)
    # pareia o app (no onboarding ou na tela de pareamento) com o Companion real, pelo código + aprovação SAS
    powershell -NoProfile -ExecutionPolicy Bypass -File "$T\uia.ps1" -Invoke "Parear celular" >/dev/null; sleep 1
    powershell -NoProfile -ExecutionPolicy Bypass -File "$T\uia.ps1" -Invoke "Código" >/dev/null; sleep 3
    CODE=$(powershell -NoProfile -ExecutionPolicy Bypass -File "$T\uia.ps1" -Texts | grep -oE "^[A-Z0-9]{4}-[A-Z0-9]{4}" | head -1)
    echo "código: $CODE"
    for i in 1 2 3 4; do "$0" tap "Parear agora" >/dev/null 2>&1 && break; "$0" tap "Continuar" >/dev/null 2>&1; sleep 2; done
    sleep 2; "$0" tap "Digitar código"; sleep 3
    $ADB shell input text "${CODE/-/}"; sleep 1; $ADB shell input keyevent 66; sleep 8
    powershell -NoProfile -ExecutionPolicy Bypass -File "$T\uia.ps1" -Texts | grep -E "^[0-9]{3} [0-9]{3}$" | sed "s/^/SAS no PC: /"
    "$0" texts | grep -E "[0-9]{3} [0-9]{3}" | sed "s/^/SAS no celular: /"
    powershell -NoProfile -ExecutionPolicy Bypass -File "$T\uia.ps1" -Invoke "Aprovar"; sleep 6; "$0" texts | head -6 ;;
  shot)
    mkdir -p "$ROOT/shots"; $ADB exec-out screencap -p > "$ROOT/shots/$2.png"; echo "$ROOT/shots/$2.png" ;;
  install)
    (cd "$ROOT/android" && ./gradlew.bat :app:assembleDebug --console=plain -q 2>&1 | grep -E "^e: |FAIL" || true)
    $ADB install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" | tail -1 ;;
  tap|hold)
    # toca no centro do elemento cujo texto (ou content-desc) contém $2
    export MSYS_NO_PATHCONV=1
    $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    XY=$($ADB shell cat /sdcard/ui.xml | node -e "
      let s=\"\";process.stdin.on(\"data\",d=>s+=d).on(\"end\",()=>{
        const q=process.argv[1];const re=/<node [^>]*>/g;let m;
        while((m=re.exec(s))){const n=m[0];const t=(n.match(/ text=\"([^\"]*)\"/)||[])[1]||\"\";const d=(n.match(/content-desc=\"([^\"]*)\"/)||[])[1]||\"\";
          if(t.includes(q)||d.includes(q)){const b=n.match(/bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"/);
            if(b){console.log(((+b[1]+ +b[3])>>1)+\" \"+((+b[2]+ +b[4])>>1));return;}}}
        process.exit(1);});" "$2") || { echo "não achei: $2"; exit 1; }
    if [ "$1" = "hold" ]; then $ADB shell input swipe $XY $XY 900; else $ADB shell input tap $XY; fi
    echo "$1 $2 @ $XY" ;;
  texts)
    export MSYS_NO_PATHCONV=1
    $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    $ADB shell cat /sdcard/ui.xml | grep -oE " text=\"[^\"]*\"| content-desc=\"[^\"]*\"" | grep -v "=\"\"" | sed "s/^ //" | head -40 ;;
  *) echo "uso: $0 host|qr|code|shot NAME|install" ;;
esac
