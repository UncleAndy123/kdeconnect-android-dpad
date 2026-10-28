#!/usr/bin/env bash
# tests/devices/full_coverage.sh - the FULL-COVERAGE gate for calling a phone "fully supported"
# (the same gate as alltechdev/vela-dpad's tests/devices/full_coverage.sh, driving KDE Connect's screens).
#
# A phone is FULLY SUPPORTED only when EVERY screen is reachable with keys alone, opens with something
# focused, and nothing is cut off, at that phone's screen size. This sets the phone's geometry with
# `wm size` / `wm density`, drives every surface with D-pad / OK / soft keys / Back / number keys only
# (never a tap), captures a numbered screenshot of each, and prints a COVERED / MISSED checklist.
# "Fully supported" needs ALL rows COVERED and the frames checked by eye.
#
#   bash tests/devices/full_coverage.sh <device-id>     # e.g. kyocera-e4810
#   bash tests/devices/full_coverage.sh all
#   PHASES="media mouse" bash tests/devices/full_coverage.sh sonim-x320   # re-capture only some surfaces
#
# Phases: firstrun device media mouse runcommand findmyphone tablet presenter settings share smssettings.
# A partial run reports PARTIAL, not a verdict - only a full run (no PHASES) can call a phone FULLY COVERED.
# RULE: every NEW keypad screen adds its surfaces as its OWN phase here (own numbered frames).
#
# Needs: one emulator or phone on adb with this app installed (debug or release), and the stand-in desktop
# running:  python3 tests/devices/standin_pc.py serve &
# The run CLEARS the app's data (it starts from a true first run and pairs with the stand-in).
DEVICES="
kyocera-cadence-s2720|240x320|160
kyocera-e4810|240x320|160
kyocera-duraxv|240x320|160
sonim-x320|480x854|320
sonim-x320-225|480x854|225
kyocera-duraxe-e4830|240x320|120
sonim-xp3|240x320|160
tcl-flip-2|240x320|160
"   # every target is its own row - each profile gets its own full-coverage record
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB="${ADB:-$(command -v adb || echo "${ANDROID_HOME:-/opt/android-sdk}/platform-tools/adb")}"
PKG="${PKG:-com.android.cts.kdeconnect}"
UI="python3 $HERE/ui.py"
STANDIN="python3 $HERE/standin_pc.py"
$ADB get-state >/dev/null 2>&1 || { echo "No device."; exit 2; }
restore() { $ADB shell wm size reset >/dev/null 2>&1; $ADB shell wm density reset >/dev/null 2>&1; }
trap restore EXIT

# Keys. On an emulator the D-pad, OK, Back and digits go in as raw key presses through the emulator console
# (milliseconds, and the same path a hardware keypad takes; scancodes from the emulator keyboard's
# qwerty.kl: 60 SOFT_LEFT, 230 SOFT_RIGHT, 232 DPAD_CENTER, 227 STAR, 228 POUND); `adb shell input keyevent`
# costs ~3 s a key there. Anything else, and every key on a real phone, uses `input keyevent`.
EMU=0; [ "$($ADB shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')" = 1 ] && EMU=1
# Newer emulator images (Android 8+) ignore console key events; there, with `adb root`, arrows, Back and
# digits are written straight to the emulator keyboard's /dev/input node (Generic.kl codes), OK and the
# soft keys still go through `input keyevent` so they arrive as DPAD_CENTER / SOFT_LEFT / SOFT_RIGHT.
RAWDEV=""
if [ $EMU = 1 ] && [ "$($ADB shell getprop ro.build.version.sdk | tr -d '\r')" -ge 26 ]; then
  EMU=0
  if [ "$($ADB shell id -u | tr -d '\r')" = 0 ]; then
    RAWDEV="$($ADB shell getevent -p 2>/dev/null | tr -d '\r' | awk '/add device/{d=$4} /name:/ && /AT Translated Set 2 keyboard/{print d}')"
  fi
fi
scancode() {
  case "$1" in
    DPAD_UP) echo 103;; DPAD_DOWN) echo 108;; DPAD_LEFT) echo 105;; DPAD_RIGHT) echo 106;;
    DPAD_CENTER) echo 232;; BACK) echo 158;; SOFT_LEFT) echo 60;; SOFT_RIGHT) echo 230;;
    STAR) echo 227;; POUND) echo 228;;
    KEYCODE_[1-9]) echo $(( ${1#KEYCODE_} + 1 ));; KEYCODE_0) echo 11;;
  esac
}
rawkey() {   # input_event structs (64-bit): EV_KEY down, SYN, EV_KEY up, SYN - as printf escapes
  python3 -c "
import struct, sys
c = int(sys.argv[1]); b = b''
for t, k, v in ((1, c, 1), (0, 0, 0), (1, c, 0), (0, 0, 0)): b += bytes(16) + struct.pack('<HHi', t, k, v)
print(''.join('\\\\x%02x' % x for x in b))" "$1"
}
key() {
  local k c
  for k in "$@"; do
    c=""; { [ $EMU = 1 ] || [ -n "$RAWDEV" ]; } && c="$(scancode "$k")"
    if [ -n "$c" ] && [ $EMU = 1 ]; then $ADB emu event send "EV_KEY:$c:1" "EV_KEY:$c:0" >/dev/null 2>&1
    elif [ -n "$c" ] && [ -n "$RAWDEV" ] && [ "$c" -lt 200 ] && [ "$c" != 60 ]; then
      # press + release in ONE write (separate sendevent calls are slow enough to trigger key repeat)
      $ADB shell "printf '$(rawkey "$c")' > $RAWDEV" >/dev/null 2>&1
    else $ADB shell input keyevent "$k" >/dev/null 2>&1; fi
    sleep "${KEY_WAIT:-1.2}"
  done
}
focus() { $UI focus; }

# Move focus until the focused view's text contains $1 ("exact" as $3: is exactly $1, for menu items like
# "Settings" next to "Plugin settings"). Lists and menus: DOWN only. With "grid" as $3
# (the device screen's plugin tiles), snake: RIGHT to the end of the row, DOWN, LEFT to the start, DOWN ...
# so it finds a tile whatever the number of columns at this screen width.
find_focus() {
  local want="${1,,}" max="${2:-30}" mode="${3:-list}" f g dir=DPAD_RIGHT i t
  f="$(focus)"
  for i in $(seq 1 "$max"); do
    t="${f#*|}"; t="${t,,}"
    if [ "$mode" = exact ]; then [ "$t" = "$want" ] && return 0
    else case "$t" in *"$want"*) return 0;; esac; fi
    if [ "$mode" = grid ]; then
      key "$dir"; g="$(focus)"
      if [ "$g" = "$f" ]; then key DPAD_DOWN; g="$(focus)"; [ "$g" = "$f" ] && return 1
        [ "$dir" = DPAD_RIGHT ] && dir=DPAD_LEFT || dir=DPAD_RIGHT; fi
    else
      key DPAD_DOWN; g="$(focus)"; [ "$g" = "$f" ] && return 1
    fi
    f="$g"
  done
  return 1
}
wait_text() { local i; for i in 1 2 3 4 5 6 7 8; do $UI has "$1" && return 0; sleep 1; done; return 1; }   # "a|b" = either
open_item() { find_focus "$1" "${2:-30}" "${3:-exact}" && key DPAD_CENTER; }
# Tiles: start the snake from the top-left tile (the last step may have left focus anywhere in the grid)
tile() { to_device || return 1; KEY_WAIT=0.3 key DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_LEFT DPAD_LEFT; open_item "$1" 30 grid; }
# Get to the device list / the paired stand-in's device screen from wherever the last step left us.
to_list() {
  launch
  wait_text "Connected devices|Multimedia control"           # the app reopens on the list or the device
  $UI has "Connected devices" && return 0
  key SOFT_LEFT; open_item "All devices" 10; wait_text "Connected devices"
}
to_device() {
  $UI has "Multimedia control" && return 0
  to_list && find_focus Desktop 6 && key DPAD_CENTER && wait_text "Multimedia control"
}

launch() {
  $ADB shell am force-stop "$PKG" >/dev/null 2>&1
  key DPAD_DOWN   # leave touch mode, or screens won't show their initial focus
  $ADB shell am start -W -n "$PKG/org.kde.kdeconnect.ui.MainActivity" >/dev/null 2>&1
  sleep "${LAUNCH_WAIT:-8}"
}

cover_one() {
  local id="$1" geom dens
  geom="$(printf '%s\n' "$DEVICES" | awk -F'|' -v id="$id" '$1==id{print $2}')"
  dens="${DENS:-$(printf '%s\n' "$DEVICES" | awk -F'|' -v id="$id" '$1==id{print $3}')}"
  [ -z "$geom" ] && { echo "unknown device '$id'"; return 1; }
  local ALLPHASES="firstrun device media mouse runcommand findmyphone tablet presenter settings share smssettings"
  local phases="${PHASES:-$ALLPHASES}" full=0; [ "$phases" = "$ALLPHASES" ] && full=1
  local cur_phase="" missed_phases=""
  phase() { case " $phases " in *" $1 "*) cur_phase="$1"; return 0;; *) return 1;; esac; }
  local out="$HERE/$id/screenshots/full"; [ -n "${DENS:-}" ] && out="$HERE/$id/screenshots/full-dens$DENS"
  mkdir -p "$out"; [ $full = 1 ] && rm -f "$out"/*.png
  local covered=0 missed=0 checklist=""

  # mark NN name EXPECT [nofocus]: wait (up to ~40 s) until EXPECT is on screen - the right screen has
  # loaded - then screenshot + checks (something focused, focus on screen, nothing cut off).
  mark() {
    local nn="$1" name="$2" expect="$3" ok=1 why rc i
    for i in 1 2 3 4 5 6; do
      sleep "${SHOT_WAIT:-1}"
      why="$($UI check "$expect")"; rc=$?
      [ $rc != 2 ] && break
    done
    $ADB exec-out screencap -p > "$out/$nn-$name.png"
    if [ $rc = 1 ] && [ "${4:-}" = nofocus ] && [ "$why" = "nothing focused" ]; then rc=0; fi
    if [ $rc = 0 ]; then covered=$((covered+1)); checklist+="  COVERED  $nn $name"$'\n'
    else missed=$((missed+1)); missed_phases+=" $cur_phase"; checklist+="  MISSED   $nn $name ($why)"$'\n'; fi
  }
  miss() { missed=$((missed+1)); missed_phases+=" $cur_phase"; checklist+="  MISSED   $1 $2 (could not reach: $3)"$'\n'; }

  echo "== $id: $geom @ $dens"
  $ADB shell wm size "$geom" >/dev/null; $ADB shell wm density "$dens" >/dev/null
  $ADB shell settings put global kdeconnect_force_dpad 1 >/dev/null
  $ADB shell settings put secure show_ime_with_hard_keyboard 0 >/dev/null

  if phase firstrun; then
    [ -z "${FIRSTRUN_NO_RESET:-}" ] && $ADB shell pm clear "$PKG" >/dev/null
    launch
    if find_focus Desktop 6 list; then mark 01 device-list Desktop; else miss 01 device-list "stand-in desktop not listed"; fi
    key SOFT_LEFT; mark 02 list-options "Trusted networks"; key BACK
    key SOFT_LEFT
    if open_item "Add devices by IP" 6; then
      mark 03 add-devices-by-ip "Add device" nofocus
      key SOFT_RIGHT; mark 04 add-device-dialog "Hostname or IP"; key BACK BACK
    else miss 03 add-devices-by-ip menu; key BACK; fi
    # Back on a slow phone takes a moment: an Options press before the list is back is lost and every later step
    # slips (it once landed keys in the Dialer), so wait for the list each time
    wait_text "Connected devices"
    key SOFT_LEFT; if open_item "Trusted networks" 6; then mark 05 trusted-networks "Allow all"; key BACK; else miss 05 trusted-networks menu; key BACK; fi
    wait_text "Connected devices"
    key SOFT_LEFT; if open_item "About" 6; then mark 06 about "Version"; key BACK; else miss 06 about menu; key BACK; fi
    wait_text "Connected devices"
    find_focus Desktop 6; key DPAD_CENTER
    mark 07 not-paired "Pair" nofocus
    key SOFT_RIGHT                                         # Pair (the stand-in accepts)
    mark 08 device "Multimedia control"
  else
    to_device
  fi

  if phase device; then
    key SOFT_LEFT; mark 09 device-options "Plugin settings"
    if open_item "Plugin settings" 8; then mark 10 plugin-settings "Battery"; key BACK; else miss 10 plugin-settings menu; key BACK; fi
  fi

  if phase media; then
    if tile "Multimedia control"; then
      mark 11 media "Clair de Lune"
      key DPAD_RIGHT                                       # Play | Devices tabs
      if find_focus "Devices" 1; then key DPAD_CENTER; mark 12 pc-volume "Speakers"; else miss 12 pc-volume "Devices tab"; fi
      key BACK
    else miss 11 media tile; fi
  fi

  if phase mouse; then
    if tile "Remote input"; then
      mark 13 keypad-mouse "Options"
      key SOFT_LEFT; mark 14 mouse-options "Compose"
      if open_item "Compose text" 8; then mark 15 compose-text "Send"; key BACK; else miss 15 compose-text menu; key BACK; fi
      key BACK
    else miss 13 keypad-mouse tile; fi
  fi

  if phase runcommand; then
    if tile "Run Command"; then
      mark 16 run-command "Check for updates"
      if open_item "Check for updates" 10 list; then mark 17 run-command-output "firefox/stable"; else miss 17 run-command-output command; fi
      key BACK
    else miss 16 run-command tile; fi
  fi

  if phase findmyphone; then
    $STANDIN send kdeconnect.findmyphone.request >/dev/null
    mark 18 find-my-phone "Found it"
    key SOFT_RIGHT                                         # Found it
  fi

  if phase tablet; then
    if tile "drawing tablet"; then
      sleep 2; KEY_WAIT=0.4 key KEYCODE_6 KEYCODE_6 KEYCODE_6 DPAD_CENTER KEYCODE_2 KEYCODE_2 KEYCODE_6 KEYCODE_6 DPAD_CENTER
      mark 19 drawing-tablet "Pen down"
      key BACK
    else miss 19 drawing-tablet tile; fi
  fi

  if phase presenter; then
    if tile "Presentation remote"; then mark 20 presentation-remote "Options"; key BACK
    else miss 20 presentation-remote tile; fi
  fi

  if phase settings; then
    to_list; key SOFT_LEFT
    if open_item "Settings" 6; then mark 21 settings "Device name"; key BACK; else miss 21 settings menu; key BACK; fi
  fi

  if phase share; then
    $ADB shell am force-stop "$PKG" >/dev/null; key DPAD_DOWN
    $ADB shell am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT hello \
      -n "$PKG/org.kde.kdeconnect.plugins.share.ShareActivity" >/dev/null 2>&1
    mark 22 share "Desktop"; key BACK
  fi

  # The SMS plugin's own settings page (Plugin settings > the "Send SMS" row's name half) and its
  # "Conversations sent to the computer" choice (issue #4)
  if phase smssettings; then
    to_device; key SOFT_LEFT
    if open_item "Plugin settings" 8 && find_focus "Send SMS" 40; then
      key DPAD_CENTER
      if find_focus "Conversations sent to the computer" 10; then
        mark 23 sms-settings "Conversations sent to the computer"
        key DPAD_CENTER; mark 24 sms-conversation-limit "Newest 20"; key BACK
      else miss 23 sms-settings "Conversations sent to the computer row"; fi
      key BACK BACK
    else miss 23 sms-settings "Send SMS in Plugin settings"; key BACK; fi
  fi

  local verdict
  if [ $full = 1 ]; then
    [ $missed = 0 ] && verdict="FULLY COVERED ($covered/$covered)" || verdict="NOT FULLY COVERED ($missed MISSED)"
  else verdict="PARTIAL ($covered covered, $missed missed; phases: $phases)"; fi
  local result; result="$(printf '%s' "$checklist"; echo "RESULT $id ($geom @ $dens, Android $($ADB shell getprop ro.build.version.release | tr -d '\r')): $verdict")"
  echo "$result"
  if [ $missed != 0 ]; then
    echo "Rerun only what failed: PHASES=\"$(echo $missed_phases | tr ' ' '\n' | sort -u | tr '\n' ' ' | sed 's/ $//')\" bash tests/devices/full_coverage.sh $id"
  fi
  [ -z "${DENS:-}" ] && { [ $full = 1 ] || [ ! -f "$HERE/$id/result.txt" ]; } && echo "$result" > "$HERE/$id/result.txt"
  [ -z "${DENS:-}" ] && [ $full = 0 ] && echo "$result" > "$HERE/$id/result-partial.txt"
  python3 "$HERE/make_grids.py" >/dev/null
}

if [ "${1:-}" = all ]; then
  printf '%s\n' "$DEVICES" | while IFS='|' read -r id _; do [ -n "$id" ] && cover_one "$id" </dev/null; done
else
  cover_one "${1:?usage: full_coverage.sh <device-id>|all}"
fi
