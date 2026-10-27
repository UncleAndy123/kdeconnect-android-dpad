#!/bin/bash
# round-perf13.sh <base apk> <base label> <new apk> <new label>: mDNS started on a worker (perf-13).
# 1. Each build: install + ahead-of-time compile, cold start x10 (coldstart.sh, median).
# 2. New build: mDNS still runs - after a cold start the log must show the discovery started and this phone's
#    service registered - and the stand-in desktop reconnects.
# 3. New build: a sampling profile of a cold start (startprof.sh), to see the NsdManager wait gone from the main
#    thread (dmtree.py).
# 4. New build: the device tour phase.
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}; export PATH=$PATH:$ANDROID_HOME/platform-tools
P=$(cd "$(dirname "$0")" && pwd); R=$P/../..
PKG=com.android.cts.kdeconnect
export STANDIN_STATE=${STANDIN_STATE:-$R/tests/devices/.standin}
L=$STANDIN_STATE/packets.jsonl
step() { echo "== $(date +%T) $*"; }
prep() {
  adb install -r -d "$1" | tail -1
  adb shell cmd package compile -m speed -f $PKG > /dev/null 2>&1
  adb shell settings put secure enabled_accessibility_services $PKG/org.kde.kdeconnect.KdeConnectAccessibilityService
  echo "installed $(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' ')"
}
for pair in "$1|$2" "$3|$4"; do
  apk=${pair%|*}; lab=${pair#*|}
  step "$lab: install"; prep "$apk"
  step "$lab: cold start x10"; bash $P/coldstart.sh "$lab" 10
done

step "$4: mDNS and reconnect after a cold start"
adb shell am force-stop $PKG; sleep 3; adb logcat -c
n=$(wc -l < $L)
adb shell am start -n $PKG/org.kde.kdeconnect.ui.MainActivity > /dev/null
s=$(date +%s); until tail -n +$((n+1)) $L | grep -q "identity over TLS"; do sleep 3; [ $(( $(date +%s)-s )) -gt 300 ] && break; done
echo "connected after $(( $(date +%s)-s ))s"; sleep 15
disc=$(adb logcat -d -s MdnsDiscovery | grep -c "Service discovery started")
reg=$(adb logcat -d -s MdnsDiscovery | grep -c "Registered ")
ok=FAIL; [ "$disc" -gt 0 ] && [ "$reg" -gt 0 ] && ok=PASS
echo "$4 MDNS $ok (discovery started: $disc, service registered: $reg)"
adb logcat -d -s MdnsDiscovery | tail -5

step "$4: startup profile"; bash $P/startprof.sh "$4" 2
python3 $P/dmtree.py $P/results/startprof-$4/start-2.trace 100 | grep -E "BackgroundService.onCreate|LanLinkProvider|NsdManager|MainActivity.onCreate|handleLaunchActivity"

step "$4: tour, device phase"
PHASES="device" timeout 1500 bash $R/tests/devices/full_coverage.sh kyocera-cadence-s2720 | grep --line-buffered -E "COVERED|MISSED|RESULT"
step "DONE"
