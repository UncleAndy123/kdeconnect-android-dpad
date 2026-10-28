#!/bin/bash
# uitime.sh <label>: key tour of every screen (no app reset), then each screen's open time from Android's
# "Displayed <activity>: +N ms" log lines.
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
P=$(cd "$(dirname "$0")" && pwd)
adb logcat -c
PHASES="device media mouse runcommand findmyphone tablet presenter settings share" bash $P/../devices/full_coverage.sh kyocera-cadence-s2720 > $P/uitour-$1.log 2>&1
adb logcat -d | grep -o "Displayed com.android.cts.kdeconnect/[^ ]*: +[0-9a-z]*" | sed 's#Displayed com.android.cts.kdeconnect/org.kde.kdeconnect.##' > $P/ui-$1.txt
echo "$1 $(tr '\n' ' ' < $P/ui-$1.txt)"
