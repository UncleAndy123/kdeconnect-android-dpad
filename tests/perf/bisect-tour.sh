#!/bin/bash
# bisect-tour.sh <phases> <label=apk>...: for each release APK, upgrade in place, reconnect, run the keypad tour
# for <phases>, keep its result line and frames in results/bisect-<label>/. To find which build broke a frame.
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
P=$(cd "$(dirname "$0")" && pwd)
export STANDIN_STATE=${STANDIN_STATE:-$P/../devices/.standin}
L=$STANDIN_STATE/packets.jsonl
PKG=com.android.cts.kdeconnect
phases=$1; shift
log=$P/results/bisect.log
say() { echo "$(date +%T) $*" | tee -a $log; }
adb root > /dev/null 2>&1; sleep 3; adb wait-for-device
for pair in "$@"; do
  lab=${pair%%=*}; apk=${pair#*=}; out=$P/results/bisect-$lab; mkdir -p $out
  adb install -r "$apk" > /dev/null 2>&1; adb shell cmd package compile -m speed -f $PKG > /dev/null 2>&1; say "$lab installed"
  n=$(wc -l < $L)
  adb shell am force-stop $PKG
  adb shell settings put secure enabled_accessibility_services $PKG/org.kde.kdeconnect.KdeConnectAccessibilityService
  adb shell settings put secure accessibility_enabled 1
  adb shell am start -n $PKG/org.kde.kdeconnect.ui.MainActivity > /dev/null
  s=$(date +%s); until tail -n +$((n+1)) $L | grep -q "identity over TLS"; do sleep 3; [ $(( $(date +%s)-s )) -gt 300 ] && break; done
  sleep 10
  PHASES="$phases" timeout 1500 bash $P/../devices/full_coverage.sh kyocera-cadence-s2720 > $out/tour.log 2>&1
  cp $P/../devices/kyocera-cadence-s2720/screenshots/full/*.png $out/ 2>/dev/null
  say "$lab tour: $(grep -E 'COVERED|MISSED' $out/tour.log | tr '\n' ' ')"
done
say "BISECT DONE"
