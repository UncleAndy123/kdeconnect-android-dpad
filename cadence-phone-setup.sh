#!/system/bin/sh
# One-time permission setup for KDE Connect (com.android.cts.kdeconnect) on an
# Android 7.1.1 phone with no touchscreen (Kyocera Cadence S2720).
#
# This runs ON THE PHONE, so it works the same from Windows (Command Prompt or
# PowerShell), macOS and Linux. Install the APK, plug the phone in with USB
# debugging on, open a terminal in the folder with this file, then run:
#
#   adb push cadence-phone-setup.sh /data/local/tmp/
#   adb shell sh /data/local/tmp/cadence-phone-setup.sh
#
# Safe to run again (after a reinstall, say): it only adds what's missing and
# keeps every other app's accessibility service and notification listener.
PKG=com.android.cts.kdeconnect

if ! pm path $PKG > /dev/null 2>&1; then
    echo "KDE Connect ($PKG) is not installed. Install the APK first."
    exit 1
fi

# Runtime permissions. Only ones that exist on 7.1.1; the rest are for newer Android.
for p in \
    READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS \
    READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS \
    READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE \
    ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION \
    CAMERA RECORD_AUDIO
do
    pm grant $PKG android.permission.$p || echo "  (could not grant $p, skipped)"
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

# Notification sync (cmd notification allow_listener is Android 8+, so use the setting directly)
append_secure enabled_notification_listeners $PKG/org.kde.kdeconnect.plugins.notifications.NotificationReceiver

# Remote keyboard + remote mouse into the phone
append_secure enabled_accessibility_services $PKG/org.kde.kdeconnect.KdeConnectAccessibilityService
settings put secure accessibility_enabled 1

# Floating clipboard button / overlays
appops set $PKG SYSTEM_ALERT_WINDOW allow

# Keep the connection alive while the phone is closed (Doze)
dumpsys deviceidle whitelist +$PKG > /dev/null

echo "Done. Open KDE Connect once so its background service starts."
