#!/bin/bash
# round-issue3-perf9.sh <installed perf-9 label> <issue-3 fix apk> <fix label>
# 1. Issue #3 baseline on the installed build: --simulate-no-store (expect FAIL: no fix there).
# 2. perf-9 focus fix in real touch mode: reboot (a keypad phone is in touch mode after a reboot until its first
#    key), strict touchmode-check.sh, then the "device" tour phase only (frames 08-10). Not "firstrun": on an
#    already paired app it opens on the device screen, not the device list.
# 3. Upgrade to the issue #3 fix and run issue3.py normally and with --simulate-no-store (expect PASS, one echo).
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}; export PATH=$PATH:$ANDROID_HOME/platform-tools
P=$(cd "$(dirname "$0")" && pwd); R=$P/../..
PKG=com.android.cts.kdeconnect
L=$P/../devices/.standin/packets.jsonl
step() { echo "== $(date +%T) $*"; }
wait_connected() {  # $1 = packet-log line count before; waits for a new TLS identity
  local s=$(date +%s); until tail -n +$(($1+1)) $L | grep -q "identity over TLS"; do sleep 3; [ $(( $(date +%s)-s )) -gt 300 ] && { echo "not connected after 300 s"; return 1; }; done
  echo "connected after $(( $(date +%s)-s )) s"
}
launch() {
  adb shell settings put secure enabled_accessibility_services $PKG/org.kde.kdeconnect.KdeConnectAccessibilityService
  adb shell settings put secure accessibility_enabled 1
  adb shell am start -n $PKG/org.kde.kdeconnect.ui.MainActivity > /dev/null
}

step "issue #3 baseline ($1, no fix), simulating a phone that doesn't store sent texts"
python3 $P/issue3.py "$1" --simulate-no-store | grep -E "echo|deleted|ISSUE3"

step "reboot for real touch mode"
adb reboot; sleep 20; adb wait-for-device
s=$(date +%s); until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 5; [ $(( $(date +%s)-s )) -gt 900 ] && { echo "no boot after 900 s"; exit 1; }; done
echo "booted after $(( $(date +%s)-s )) s"; adb root > /dev/null; sleep 5; adb wait-for-device; sleep 60
adb shell dumpsys window | grep -o "mInTouchMode=[a-z]*" | head -1
step "touch-mode check ($1)"
bash $P/touchmode-check.sh "$1-reboot"
step "tour: device phase ($1)"
PHASES="device" timeout 1500 bash $R/tests/devices/full_coverage.sh kyocera-cadence-s2720 | grep -E "COVERED|MISSED|RESULT"

step "upgrade to the issue #3 fix ($3)"
adb install -r "$2" | tail -1
adb shell cmd package compile -m speed -f $PKG > /dev/null 2>&1; echo "compiled ahead of time"
n=$(wc -l < $L); launch; wait_connected $n; sleep 15
step "issue #3 with the fix: normal phone"
python3 $P/issue3.py "$3" | grep -E "echo|ISSUE3"
step "issue #3 with the fix: phone that doesn't store sent texts"
python3 $P/issue3.py "$3" --simulate-no-store | grep -E "echo|deleted|ISSUE3"
step "DONE"
