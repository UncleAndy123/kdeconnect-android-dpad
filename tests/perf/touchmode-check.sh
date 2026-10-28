#!/bin/bash
# touchmode-check.sh <label>: a keypad phone is in touch mode after a reboot until its first key press. Put the
# emulator in touch mode (a tap), cold-launch the app, and check the device screen opened with a control focused
# (not the whole page). PASS/FAIL line + screenshot in results/.
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
P=$(cd "$(dirname "$0")" && pwd)
PKG=com.android.cts.kdeconnect
out=$P/results/touchmode-$1; mkdir -p $out
adb shell am force-stop $PKG
tm=$(adb shell dumpsys window | grep -o "mInTouchMode=[a-z]*" | head -1)
# A tap doesn't always switch a no-touchscreen emulator into touch mode; a reboot does (until the first key)
[ "$tm" = "mInTouchMode=true" ] || { adb shell input tap 5 5; sleep 1; tm=$(adb shell dumpsys window | grep -o "mInTouchMode=[a-z]*" | head -1); }
if [ "$tm" != "mInTouchMode=true" ]; then
  echo "$1 TOUCHMODE INVALID (not in touch mode before launch: $tm; reboot the emulator and run this before any key)" | tee $out/result.txt
  exit 1
fi
adb shell am start -n $PKG/org.kde.kdeconnect.ui.MainActivity > /dev/null
sleep 20
f=$(python3 $P/../devices/ui.py focus 2>/dev/null | head -1)
adb exec-out screencap -p > $out/device-screen.png
# The whole-page ring = the focused node is as big as the content area; a tile is much smaller
ok=FAIL; case "$f" in *"|"*) b=${f%%|*}; w=$(echo "$b" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\3-\1/' | bc 2>/dev/null); [ -n "$w" ] && [ "$w" -lt 200 ] && ok=PASS;; esac
echo "$1 TOUCHMODE $ok ($tm before launch; focused: ${f:-nothing})" | tee $out/result.txt
