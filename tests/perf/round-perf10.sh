#!/bin/bash
# round-perf10.sh <apk> <label>: the navigation drawer without Material's NavigationView (perf-10).
# 1. fresh-install.sh: uninstall, install, setup, pairing through the keypad first-run phase (frames 01-08).
# 2. Tour phase "device" (frames 09-10).
# 3. The drawer itself: a keypad phone never shows it, so make the emulator tablet-wide (820 dp or more picks
#    layout-w820dp, where the drawer is always visible; Android 7 caps `wm size` at twice the physical screen, so a
#    low density makes it wide enough: 640x480 @ 80 = 960 dp), screenshot it and check its rows: the device name header,
#    the paired stand-in "Desktop" (checked), and the Pair / Settings / About entries.
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}; export PATH=$PATH:$ANDROID_HOME/platform-tools
P=$(cd "$(dirname "$0")" && pwd); R=$P/../..
PKG=com.android.cts.kdeconnect
step() { echo "== $(date +%T) $*"; }
out=$P/results/perf10-$2; mkdir -p $out

step "fresh install of $2 (pairs through the first-run phase)"
bash $P/fresh-install.sh "$1"
grep -E "first run|connected|DONE" $P/fresh-install.log
grep -E "COVERED|MISSED|RESULT" $P/uitour-firstrun.log

step "tour: device phase"
PHASES="device" timeout 1500 bash $R/tests/devices/full_coverage.sh kyocera-cadence-s2720 | grep --line-buffered -E "COVERED|MISSED|RESULT"

step "drawer on a tablet-sized screen"
adb shell wm size 640x480; adb shell wm density 80
adb shell am force-stop $PKG; sleep 2
adb shell am start -n $PKG/org.kde.kdeconnect.ui.MainActivity > /dev/null; sleep 25
adb exec-out screencap -p > $out/drawer-tablet.png
adb exec-out uiautomator dump /dev/tty > $out/drawer-tablet.xml 2>/dev/null
ok=PASS
for want in "Desktop" "Pair new device" "Settings" "About"; do
  if grep -q "text=\"$want" $out/drawer-tablet.xml; then echo "  drawer shows: $want"; else echo "  drawer MISSING: $want"; ok=FAIL; fi
done
adb shell wm size reset; adb shell wm density reset
echo "$2 DRAWER $ok" | tee $out/result.txt
step "DONE"
