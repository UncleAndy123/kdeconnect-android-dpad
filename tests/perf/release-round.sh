#!/bin/bash
# release-round.sh <label> <release apk> [phases]
#
# The standard test round for a CI release APK, upgrading the app IN PLACE on the emulator (same CI signing key, so
# the pairing with the stand-in desktop, permissions and settings stay). Needs: the emulator running and paired
# (see fresh-install.sh for a first install), and the stand-in desktop running (tests/devices/standin_pc.py serve).
#
# Runs, in order, logging one line per step to round-<label>.log next to this script:
#   install + ahead-of-time compile, cold start x3 / idle CPU / memory (prof.sh), a startup profile (dmtrace.py),
#   reconnect, accessibility service on (AFTER connecting: also checks the keyboard/mouse plugins load without a
#   reconnect), conversation list + contacts + sent SMS x3 (measure.py), conversation list correctness (convcheck.py),
#   incoming SMS + early notification (incoming.py), opening a long thread (openconv.py), album art (artcheck.py),
#   the 13-feature sweep (sweep.py), then the keypad tour for [phases] (default: all but firstrun) with screen open
#   times, and the screens the tour can't reach (extra.sh).
#
# Watch it: tail -f round-<label>.log. A step that writes nothing for 4+ minutes is stuck (uiautomator dumps take
# 5-13 s each on the emulator, so the tour is slow, but not that slow).
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
P=$(cd "$(dirname "$0")" && pwd)
export STANDIN_STATE=${STANDIN_STATE:-$P/../devices/.standin}
L=$STANDIN_STATE/packets.jsonl
PKG=com.android.cts.kdeconnect
A11Y=$PKG/org.kde.kdeconnect.KdeConnectAccessibilityService
lab=$1; apk=$2; phases=${3:-"device media mouse runcommand findmyphone tablet presenter settings share"}
log=$P/round-$lab.log; : > $log
say() { echo "$(date +%T) $*" >> $log; }

adb root > /dev/null 2>&1; sleep 3; adb wait-for-device
adb install -r "$apk" >> $log 2>&1; say "installed $(basename "$apk")"
adb shell cmd package compile -m speed -f $PKG > /dev/null 2>&1; say "compiled ahead of time"

say "start prof"; timeout 600 bash $P/prof.sh $lab > $P/prof-$lab.txt 2>&1; say "done prof: $(tail -1 $P/prof-$lab.txt)"
adb shell am force-stop $PKG; sleep 2; adb shell rm -f /data/local/tmp/start.trace
adb shell am start -W --start-profiler /data/local/tmp/start.trace --sampling 1000 -n $PKG/org.kde.kdeconnect.ui.MainActivity > /dev/null; sleep 8
adb shell am profile stop $PKG; sleep 3; adb pull /data/local/tmp/start.trace $P/start-$lab.trace > /dev/null 2>&1
say "startup profile (main thread, inflated by the profiler): $(python3 $P/dmtrace.py $P/start-$lab.trace main 400 | grep -E 'refreshUI|MainActivity.onCreate|ViewRootImpl.performTraversals' | tr -s ' ' | tr '\n' ';')"

# Reconnect. Android 7 drops an app's accessibility service when it is force-stopped (prof.sh force-stops it), so it
# is turned on again AFTER the app connected - which also tests that the keyboard/mouse plugins then load at once.
n=$(wc -l < $L)
adb shell am force-stop $PKG
adb shell am start -n $PKG/org.kde.kdeconnect.ui.MainActivity > /dev/null
s=$(date +%s); until tail -n +$((n+1)) $L | grep -q "identity over TLS"; do sleep 3; [ $(( $(date +%s)-s )) -gt 300 ] && break; done
say "connected after $(( $(date +%s)-s ))s"; sleep 10
adb logcat -c
adb shell settings put secure enabled_accessibility_services $A11Y
adb shell settings put secure accessibility_enabled 1; sleep 10
say "accessibility: $(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"

for t in measure measure measure convcheck incoming openconv artcheck sweep; do
  say "start $t"; timeout 900 python3 $P/$t.py $lab > $P/$t-$lab.txt 2>&1; say "done $t: $(tail -1 $P/$t-$lab.txt | cut -c1-700)"
done

say "start tour ($phases)"; adb logcat -c
PHASES="$phases" timeout 2400 bash $P/../devices/full_coverage.sh kyocera-cadence-s2720 > $P/uitour-$lab.log 2>&1
adb logcat -d | grep -o "Displayed $PKG/[^ ]*: +[0-9a-z]*" | sed "s#Displayed $PKG/org.kde.kdeconnect.##" > $P/ui-$lab.txt
say "tour: $(grep -E 'COVERED|MISSED' $P/uitour-$lab.log | tr '\n' ' ' | cut -c1-1200)"
say "screen open times: $(tr '\n' ' ' < $P/ui-$lab.txt)"
say "start extra"; timeout 300 bash $P/extra.sh $lab > $P/extra-$lab.txt 2>&1; say "done extra: $(grep -E 'PASS|FAIL' $P/extra-$lab.txt | tr '\n' ' ')"
say "ALLDONE"
