#!/bin/sh
# macOS / Linux shortcut for cadence-phone-setup.sh (the permission setup for
# com.android.cts.kdeconnect on an Android 7.1.1 keypad phone). On Windows, or
# anywhere else, run these two commands from this folder instead; they work in
# Command Prompt, PowerShell and any terminal:
#
#   adb push cadence-phone-setup.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/cadence-phone-setup.sh
#
# Install the APK first and plug the phone in with USB debugging on.
# Set ADB_SERIAL to pick a phone when several are connected.
set -e
cd "$(dirname "$0")"
A="adb ${ADB_SERIAL:+-s $ADB_SERIAL}"
$A push cadence-phone-setup.sh /data/local/tmp/ > /dev/null
$A shell sh /data/local/tmp/cadence-phone-setup.sh
