#!/bin/bash
# extra.sh <label>: the converted screens the keypad tour doesn't reach. Opens each one directly, waits for
# its text, screenshots it, checks the focus ring target and the soft keys. Prints PASS/FAIL per screen.
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
P=$(cd "$(dirname "$0")" && pwd)
UI=$P/../devices/ui.py
PKG=com.android.cts.kdeconnect
ID=standin_pc_0123456789abcdef0123456789
adb root > /dev/null 2>&1; sleep 3; adb wait-for-device
out=$P/extra-$1; mkdir -p $out
pass=0; fail=0
shot() { # name activity expect [extras...]
  local name=$1 act=$2 want=$3; shift 3
  adb shell am start -n $PKG/$act "$@" > /dev/null
  local ok=0
  for i in 1 2 3 4 5 6; do python3 $UI has "$want" > /dev/null 2>&1 && { ok=1; break; }; sleep 2; done
  adb exec-out screencap -p > $out/$name.png
  local f=$(python3 $UI focus 2>/dev/null | head -1)
  if [ $ok = 1 ]; then echo "PASS $name (focus: $f)"; pass=$((pass+1)); else echo "FAIL $name: '$want' not on screen"; fail=$((fail+1)); fi
  adb shell input keyevent BACK; sleep 1
}
[ -z "$ONLY" ] && shot send-text org.kde.kdeconnect.plugins.mousepad.ComposeSendActivity "Send|Clear" --es deviceId $ID
shot custom-devices org.kde.kdeconnect.ui.CustomDevicesActivity "Add device|If your device"
[ -z "$ONLY" ] && shot licenses org.kde.kdeconnect.ui.about.LicensesActivity "Options|License|Copyright"
[ -z "$ONLY" ] && shot about-kde org.kde.kdeconnect.ui.about.AboutKDEActivity "be Free|KDE"
[ -z "$ONLY" ] && shot easter-egg org.kde.kdeconnect.ui.about.EasterEggActivity "°"
echo "$1 EXTRA $pass passed, $fail failed"
