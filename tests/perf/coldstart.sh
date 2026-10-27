#!/bin/bash
# coldstart.sh <label> [runs]: cold start of the main screen, many times (default 10), with the median.
# Cold start on the unaccelerated emulator varies by a second or more between runs, so a change worth a few hundred
# ms only shows in the median of many. Each run: force-stop, wait, `am start -W` (TotalTime = until the first frame).
export PATH=$PATH:${ANDROID_HOME:-/opt/android-sdk}/platform-tools
PKG=com.android.cts.kdeconnect
N=${2:-10}
P=$(cd "$(dirname "$0")" && pwd)
ts=()
for i in $(seq 1 $N); do
  adb shell am force-stop $PKG; sleep 4
  t=$(adb shell am start -W -n $PKG/org.kde.kdeconnect.ui.MainActivity | tr -d '\r' | awk '/TotalTime/{print $2}')
  ts+=("$t"); sleep 6
done
sorted=$(printf '%s\n' "${ts[@]}" | sort -n | tr '\n' ' ')
median=$(printf '%s\n' "${ts[@]}" | sort -n | awk '{a[NR]=$1} END {print (NR%2 ? a[(NR+1)/2] : int((a[NR/2]+a[NR/2+1])/2))}')
line="$1 cold_start_ms median=$median runs=[ $sorted]"
echo "$line"
mkdir -p $P/results; echo "$line" >> $P/results/coldstart.txt
