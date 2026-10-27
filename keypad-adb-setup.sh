#!/bin/sh
# macOS / Linux shortcut for keypad-phone-setup.sh (the permission setup for
# com.android.cts.kdeconnect on a keypad phone, Android 7.1 to 11). On Windows, or
# anywhere else, run these two commands from this folder instead; they work in
# Command Prompt, PowerShell and any terminal:
#
#   adb push keypad-phone-setup.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/keypad-phone-setup.sh
#
# Install the APK first and plug the phone in with USB debugging on.
# Set ADB_SERIAL to pick a phone when several are connected.
set -e
cd "$(dirname "$0")"
A="adb ${ADB_SERIAL:+-s $ADB_SERIAL}"
$A push keypad-phone-setup.sh /data/local/tmp/ > /dev/null
$A shell sh /data/local/tmp/keypad-phone-setup.sh
