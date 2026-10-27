#!/bin/bash
# round-perf11.sh <base apk> <base label> <new apk> <new label>: faster start (perf-11).
# For each build: install + ahead-of-time compile, cold start 10x (coldstart.sh, median), a screenshot of the device
# screen (the tiles must look the same). Then, on the new build, every tour phase that opens a screen from a tile.
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}; export PATH=$PATH:$ANDROID_HOME/platform-tools
P=$(cd "$(dirname "$0")" && pwd); R=$P/../..
PKG=com.android.cts.kdeconnect
step() { echo "== $(date +%T) $*"; }
out=$P/results/perf11; mkdir -p $out
prep() {
  adb install -r -d "$1" | tail -1
  adb shell cmd package compile -m speed -f $PKG > /dev/null 2>&1
  adb shell settings put secure enabled_accessibility_services $PKG/org.kde.kdeconnect.KdeConnectAccessibilityService
  adb shell settings put secure accessibility_enabled 1
  echo "installed $(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' ')"
}
shot() {
  adb shell am force-stop $PKG; sleep 3
  adb shell am start -n $PKG/org.kde.kdeconnect.ui.MainActivity > /dev/null; sleep 20
  adb exec-out screencap -p > $out/device-screen-$1.png
}
for pair in "$1|$2" "$3|$4"; do
  apk=${pair%|*}; lab=${pair#*|}
  step "$lab: install"; prep "$apk"
  step "$lab: cold start x10"; bash $P/coldstart.sh "$lab" 10
  step "$lab: device screen"; shot "$lab"
done
step "$4: tour phases that open tiles"
PHASES="device media runcommand findmyphone tablet presenter" timeout 2400 bash $R/tests/devices/full_coverage.sh kyocera-cadence-s2720 | grep --line-buffered -E "COVERED|MISSED|RESULT"
step "DONE"
