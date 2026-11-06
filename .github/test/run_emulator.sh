#!/bin/bash
# Läuft im Emulator-Schritt: App installieren, Test-Konto eintragen, starten, Bildschirmfotos und Log sammeln
set -x
OUT=$GITHUB_WORKSPACE/emu-out
mkdir -p $OUT
APK=android/app/build/outputs/apk/debug/app-debug.apk
adb wait-for-device
adb shell settings put global window_animation_scale 1
adb install -r $APK || exit 1
adb logcat -c
cat > /tmp/iptvplus.xml <<'X'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="accounts">[{&quot;label&quot;:&quot;Test&quot;,&quot;server&quot;:&quot;http://10.0.2.2:8080&quot;,&quot;user&quot;:&quot;test&quot;,&quot;pass&quot;:&quot;test&quot;}]</string>
    <int name="activeIndex" value="0" />
</map>
X
adb push /tmp/iptvplus.xml /data/local/tmp/iptvplus.xml
adb shell "run-as app.iptvplus.tv mkdir -p /data/data/app.iptvplus.tv/shared_prefs"
adb shell "run-as app.iptvplus.tv cp /data/local/tmp/iptvplus.xml /data/data/app.iptvplus.tv/shared_prefs/iptvplus.xml"
adb shell "run-as app.iptvplus.tv ls -la /data/data/app.iptvplus.tv/shared_prefs"
adb shell am start -n app.iptvplus.tv/.MainActivity
shot() { sleep ${2:-4}; adb exec-out screencap -p > $OUT/$1.png; adb shell "pidof app.iptvplus.tv" > /dev/null || echo "APP LAEUFT NICHT MEHR nach $1" | tee -a $OUT/status.txt; }
key() { adb shell input keyevent $1; }
shot 01_start 25
shot 02_start_spaeter 20
key KEYCODE_DPAD_DOWN; shot 03_reihe1 3
key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_RIGHT; shot 04_rechts 3
key KEYCODE_DPAD_DOWN; shot 05_reihe2 3
key KEYCODE_DPAD_CENTER; shot 06_detail 6
key KEYCODE_BACK; shot 07_zurueck 3
key KEYCODE_BACK; shot 08_reiter 3
key KEYCODE_DPAD_RIGHT; shot 09_live 15
key KEYCODE_DPAD_DOWN; shot 10_live_reihe 3
key KEYCODE_DPAD_UP; key KEYCODE_DPAD_UP; key KEYCODE_DPAD_RIGHT; shot 11_filme 15
key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN; shot 12_filme_reihe 4
key KEYCODE_BACK; key KEYCODE_DPAD_RIGHT; shot 13_serien 12
key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_CENTER; shot 14_serie_detail 6
key KEYCODE_DPAD_DOWN; shot 15_staffeln 3
key KEYCODE_DPAD_DOWN; shot 16_folgen 3
key KEYCODE_BACK; key KEYCODE_BACK; key KEYCODE_DPAD_RIGHT; shot 17_programm 8
key KEYCODE_DPAD_RIGHT; shot 18_suche 4
key KEYCODE_DPAD_RIGHT; shot 19_einstellungen 4
adb logcat -d > $OUT/logcat.txt
grep -E "FATAL EXCEPTION|AndroidRuntime|OutOfMemory|ANR in" -A 40 $OUT/logcat.txt > $OUT/crash.txt || true
adb logcat -d -b crash > $OUT/crash_buffer.txt || true
adb shell "run-as app.iptvplus.tv cat files/last_crash.txt" > $OUT/last_crash.txt 2>/dev/null || true
exit 0
