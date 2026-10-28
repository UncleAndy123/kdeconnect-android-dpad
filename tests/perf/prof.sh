#!/bin/bash
# prof.sh <label>: cold start time (3 runs), idle CPU while connected (60 s), memory (PSS)
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
PKG=com.android.cts.kdeconnect
starts=""
for i in 1 2 3; do
  adb shell am force-stop $PKG; sleep 3
  t=$(adb shell am start -W -n $PKG/org.kde.kdeconnect.ui.MainActivity | tr -d '\r' | awk '/TotalTime/{print $2}')
  starts="$starts $t"; sleep 8
done
adb shell input keyevent HOME; sleep 30   # let it connect and settle
pid=$(adb shell pidof $PKG | tr -d '\r')
cpu() { adb shell cat /proc/$pid/stat | awk '{print $14+$15}'; }
a=$(cpu); sleep 60; b=$(cpu)
hz=100
pss=$(adb shell dumpsys meminfo $PKG | tr -d '\r' | awk '/TOTAL/{print $2; exit}')
echo "$1 cold_start_ms=[$starts ] idle_cpu_60s=$(( (b-a)*1000/hz ))ms pss_kb=$pss"
