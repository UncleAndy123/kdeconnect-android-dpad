#!/bin/bash
# round-vela30.sh <apk> <device-id>... : the vela phones' full keypad tour on the Android 11 AVD (`dpad30`).
# Waits for boot, prepares the emulator (root, no animations, no crash dialogs), installs the APK, runs the phone
# setup script and the ahead-of-time compile (without it the cold start on this emulator misses Android 11's
# startForegroundService deadline), then the full tour (every phase) of each device id in turn.
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}; export PATH=$PATH:$ANDROID_HOME/platform-tools
P=$(cd "$(dirname "$0")" && pwd); R=$P/../..
PKG=com.android.cts.kdeconnect
step() { echo "== $(date +%T) $*"; }
apk=$1; shift
step "waiting for boot"
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 10; done
adb root > /dev/null 2>&1; sleep 5; adb wait-for-device
# On this unaccelerated emulator the first minutes after boot are so slow that system_server's watchdog can kill it
# (sys.boot_completed stays 1 while it restarts, and every `cmd` then fails): wait until the package service has
# answered for 5 minutes in a row.
up=0; while [ $up -lt 10 ]; do
  if timeout 30 adb shell service check package 2>/dev/null | grep -q ": found"; then up=$((up+1)); else up=0; fi; sleep 30
done
for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global $s 0; done
adb shell settings put global hide_error_dialogs 1
adb shell settings put secure show_ime_with_hard_keyboard 0
step "Android $(adb shell getprop ro.build.version.release | tr -d '\r'), install $(basename $apk)"
adb uninstall $PKG > /dev/null 2>&1
adb install "$apk" | tail -1
adb shell pm path $PKG > /dev/null 2>&1 || { echo "install FAILED"; exit 1; }
adb push $R/keypad-phone-setup.sh /data/local/tmp/ > /dev/null
adb shell sh /data/local/tmp/keypad-phone-setup.sh | tail -3
adb shell cmd package compile -m speed -f $PKG | tail -1
echo "installed $(adb shell dumpsys package $PKG | grep -m1 versionName | tr -d ' \r')"
for id in "$@"; do
  step "$id: full tour"
  timeout 5400 bash $R/tests/devices/full_coverage.sh "$id" | grep --line-buffered -E "COVERED|MISSED|RESULT|Rerun"
done
step "DONE"
