#!/bin/bash
# startprof.sh <label> [runs]: where a cold start spends its time. Each run (default 3): force-stop, start the main
# screen with the sampling profiler (1 ms), stop it 8 s later, pull the trace; then prints dmtrace.py's inclusive
# time per method on the main thread for each trace (sampling: the totals are real time, not inflated much).
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
PKG=com.android.cts.kdeconnect
P=$(cd "$(dirname "$0")" && pwd)
out=$P/results/startprof-$1; mkdir -p $out
for i in $(seq 1 ${2:-3}); do
  adb shell am force-stop $PKG; sleep 4; adb shell rm -f /data/local/tmp/start.trace
  adb shell am start -W --start-profiler /data/local/tmp/start.trace --sampling 1000 -n $PKG/org.kde.kdeconnect.ui.MainActivity | tr -d '\r' | grep TotalTime
  sleep 8; adb shell am profile stop $PKG; sleep 3
  adb pull /data/local/tmp/start.trace $out/start-$i.trace > /dev/null 2>&1
  python3 $P/dmtrace.py $out/start-$i.trace main 70 > $out/main-$i.txt
done
echo "traces and main-thread tables in $out"
