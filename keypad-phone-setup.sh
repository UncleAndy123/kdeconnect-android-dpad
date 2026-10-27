#!/system/bin/sh
# One-time permission setup for KDE Connect (com.android.cts.kdeconnect) on a
# keypad phone with no touchscreen (Android 7.1 to 11).
#
# This runs ON THE PHONE, so it works the same from Windows (Command Prompt or
# PowerShell), macOS and Linux. Install the APK, plug the phone in with USB
# debugging on, open a terminal in the folder with this file, then run:
#
#   adb push keypad-phone-setup.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/keypad-phone-setup.sh
#
# Safe to run again (after a reinstall, say): it only adds what's missing and
# keeps every other app's accessibility service and notification listener.
PKG=com.android.cts.kdeconnect

if ! pm path $PKG > /dev/null 2>&1; then
    echo "KDE Connect ($PKG) is not installed. Install the APK first."
    exit 1
fi

# Runtime permissions. Ones this phone's Android doesn't have are skipped (the last four are Android 10-12+).
for p in \
    READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS \
    READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS \
    READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE \
    ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION \
    CAMERA RECORD_AUDIO \
    POST_NOTIFICATIONS BLUETOOTH_CONNECT BLUETOOTH_SCAN
do
    pm grant $PKG android.permission.$p > /dev/null 2>&1 || echo "  ($p not available on this phone, skipped)"
done

# Adds a component to a ':'-separated secure setting without dropping what's already there.
append_secure() {
    key=$1; val=$2
    cur=$(settings get secure "$key")
    case "$cur" in
        *"$val"*) ;;
        null|"") settings put secure "$key" "$val" ;;
        *) settings put secure "$key" "$cur:$val" ;;
    esac
}

# Notification sync (cmd notification allow_listener is Android 8+; Android 7 takes the setting directly)
LISTENER=$PKG/org.kde.kdeconnect.plugins.notifications.NotificationReceiver
cmd notification allow_listener $LISTENER > /dev/null 2>&1 || append_secure enabled_notification_listeners $LISTENER

# Remote keyboard + remote mouse into the phone
append_secure enabled_accessibility_services $PKG/org.kde.kdeconnect.KdeConnectAccessibilityService
settings put secure accessibility_enabled 1

# Floating clipboard button / overlays
appops set $PKG SYSTEM_ALERT_WINDOW allow

# Android 11+: browsing all files from the computer; Android 14+: Find my phone rings full screen
appops set $PKG MANAGE_EXTERNAL_STORAGE allow > /dev/null 2>&1
appops set $PKG USE_FULL_SCREEN_INTENT allow > /dev/null 2>&1

# Keep the connection alive while the phone is closed (Doze)
dumpsys deviceidle whitelist +$PKG > /dev/null

# Compile the whole app ahead of time. Android 7-9 otherwise runs a freshly installed app interpreted and
# compiles it bit by bit over the following days; on a low-end phone that makes everything slower until then.
# (An app update resets this: run this script again after updating.)
echo "Compiling KDE Connect ahead of time (can take a few minutes on a slow phone)..."
cmd package compile -m speed -f $PKG > /dev/null 2>&1 || echo "  (could not compile ahead of time, skipped)"

echo "Done. Open KDE Connect once so its background service starts."
