#!/bin/bash
# fresh-install.sh <release apk>
#
# First install of a CI release APK on the emulator (or switching from a debug build, whose signing key differs):
# uninstall, install, phone setup script, pair with the stand-in desktop through the keypad first-run phase, then
# the setup script AGAIN (the first-run phase clears the app's data, and clearing data revokes every permission),
# and the Contacts "share contacts" consent a user gives once in the app. After this, release-round.sh can upgrade
# in place.
#
# Gotchas this handles (each one cost a round on 26-27 Oct 2026):
#  - pm clear (first-run phase) revokes runtime permissions: run the setup script after pairing.
#  - ContactsPlugin needs acceptedToTransferContacts=true in <deviceId>_ContactsPlugin_preferences.xml (per device);
#    a prefs file written by the root shell gets the wrong SELinux categories and the app can't read it: chcon it
#    to the label of the app's own prefs files.
#  - A force-stop turns the app's accessibility service off on Android 7: enable it after the last force-stop.
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
P=$(cd "$(dirname "$0")" && pwd)
export STANDIN_STATE=${STANDIN_STATE:-$P/../devices/.standin}
L=$STANDIN_STATE/packets.jsonl
PKG=com.android.cts.kdeconnect
STANDIN_ID=standin_pc_0123456789abcdef0123456789
log=$P/fresh-install.log; : > $log
say() { echo "$(date +%T) $*" >> $log; }

adb root > /dev/null 2>&1; sleep 3; adb wait-for-device
adb uninstall $PKG >> $log 2>&1
adb install "$1" >> $log 2>&1; say "installed"
setup() {
  adb push $P/../../keypad-phone-setup.sh /data/local/tmp/ > /dev/null
  adb shell sh /data/local/tmp/keypad-phone-setup.sh >> $log 2>&1
}
setup; say "setup done (includes the ahead-of-time compile)"
say "pairing through the first-run phase"
PHASES="firstrun" timeout 1200 bash $P/../devices/full_coverage.sh kyocera-cadence-s2720 > $P/uitour-firstrun.log 2>&1
say "first run: $(grep -E 'COVERED|MISSED' $P/uitour-firstrun.log | tr '\n' ' ')"
setup; say "setup re-run after pairing: $(adb shell dumpsys package $PKG | grep -c 'granted=true') permissions granted"
D=/data/data/$PKG/shared_prefs; F=$D/${STANDIN_ID}_ContactsPlugin_preferences.xml
adb shell "echo \"<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><boolean name='acceptedToTransferContacts' value='true' /></map>\" > $F; chown \$(stat -c %u:%g $D) $F; chmod 660 $F; chcon \$(ls -Zd $D/$PKG""_preferences.xml | cut -d' ' -f1) $F"
say "contacts consent: $(adb shell ls -Z $F)"
n=$(wc -l < $L)
adb shell am force-stop $PKG
adb shell settings put secure enabled_accessibility_services $PKG/org.kde.kdeconnect.KdeConnectAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell am start -n $PKG/org.kde.kdeconnect.ui.MainActivity > /dev/null
s=$(date +%s); until tail -n +$((n+1)) $L | grep -q "identity over TLS"; do sleep 3; [ $(( $(date +%s)-s )) -gt 300 ] && break; done
say "connected after $(( $(date +%s)-s ))s"
say "plugins without permission (expect only RemoteKeyboardIMEPlugin): $(adb logcat -d | grep 'addPlugin: No permission' | tail -4 | awk '{print $NF}' | tr '\n' ' ')"
say "DONE"
