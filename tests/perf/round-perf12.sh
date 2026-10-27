#!/bin/bash
# round-perf12.sh <base apk> <base label> <new apk> <new label>: pairing notification / pairing scope (perf-12).
# 1. Cold start x10 on each build (coldstart.sh, median).
# 2. On the new build, the pairing notification still shows and goes: the stand-in desktop unpairs, asks to pair
#    (the phone must post its pairing notification), then cancels (the notification must go).
# 3. fresh-install.sh of the new build: pairs through the keypad first run (the pairing timers use the scope made on
#    first use), then the device tour phase.
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}; export PATH=$PATH:$ANDROID_HOME/platform-tools
P=$(cd "$(dirname "$0")" && pwd); R=$P/../..
PKG=com.android.cts.kdeconnect
STANDIN="python3 $R/tests/devices/standin_pc.py send"
step() { echo "== $(date +%T) $*"; }
prep() {
  adb install -r -d "$1" | tail -1
  adb shell cmd package compile -m speed -f $PKG > /dev/null 2>&1
  adb shell settings put secure enabled_accessibility_services $PKG/org.kde.kdeconnect.KdeConnectAccessibilityService
  echo "installed $(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' ')"
}
pairing_notif() { adb shell dumpsys notification --noredact | grep -c "Pairing request from"; }
for pair in "$1|$2" "$3|$4"; do
  apk=${pair%|*}; lab=${pair#*|}
  step "$lab: install"; prep "$apk"
  step "$lab: cold start x10"; bash $P/coldstart.sh "$lab" 10
done

step "$4: pairing notification"
adb shell am start -n $PKG/org.kde.kdeconnect.ui.MainActivity > /dev/null; sleep 20
adb shell input keyevent HOME; sleep 3   # in the background, an incoming request is a notification
$STANDIN kdeconnect.pair '{"pair": false}'; sleep 8
before=$(pairing_notif)
$STANDIN kdeconnect.pair "{\"pair\": true, \"timestamp\": $(date +%s)}"; sleep 12
shown=$(pairing_notif)
$STANDIN kdeconnect.pair '{"pair": false}'; sleep 10
after=$(pairing_notif)
ok=FAIL; [ "$before" = 0 ] && [ "$shown" -gt 0 ] && [ "$after" = 0 ] && ok=PASS
echo "$4 PAIRING-NOTIFICATION $ok (before request: $before, after request: $shown, after cancel: $after)"

step "$4: fresh install, pairing through the first run, device phase"
bash $P/fresh-install.sh "$3"
grep -E "first run|connected" $P/fresh-install.log
PHASES="device" timeout 1500 bash $R/tests/devices/full_coverage.sh kyocera-cadence-s2720 | grep --line-buffered -E "COVERED|MISSED|RESULT"
step "DONE"
