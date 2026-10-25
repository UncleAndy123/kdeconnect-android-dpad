#!/bin/sh
# One-time permission setup for KDE Connect (com.android.cts.kdeconnect) on an
# Android 7.1.1 phone with no touchscreen (Kyocera Cadence S2720).
# Install the APK first, plug the phone in with USB debugging on, then run this.
# Run it again after reinstalling; adb uninstall clears all of it.
set -e
PKG=com.android.cts.kdeconnect
A="adb ${ADB_SERIAL:+-s $ADB_SERIAL} shell"

# Runtime permissions. Only ones that exist on 7.1.1; the rest are for newer Android.
for p in \
    READ_SMS SEND_SMS RECEIVE_SMS RECEIVE_MMS \
    READ_PHONE_STATE READ_CALL_LOG READ_CONTACTS \
    READ_EXTERNAL_STORAGE WRITE_EXTERNAL_STORAGE \
    ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION \
    CAMERA RECORD_AUDIO
do
    $A pm grant $PKG android.permission.$p
done

# Adds a component to a ':'-separated secure setting without dropping what's already there.
append_secure() {
    key=$1; val=$2
    cur=$($A settings get secure "$key" | tr -d '\r')
    case "$cur" in
        *"$val"*) ;;
        null|"") $A settings put secure "$key" "$val" ;;
        *) $A settings put secure "$key" "$cur:$val" ;;
    esac
}

# Notification sync (cmd notification allow_listener is Android 8+, so use the setting directly)
append_secure enabled_notification_listeners $PKG/org.kde.kdeconnect.plugins.notifications.NotificationReceiver

# Remote keyboard + remote mouse into the phone
append_secure enabled_accessibility_services $PKG/org.kde.kdeconnect.KdeConnectAccessibilityService
$A settings put secure accessibility_enabled 1

# Floating clipboard button / overlays
$A appops set $PKG SYSTEM_ALERT_WINDOW allow

# Keep the connection alive while the phone is closed (Doze)
$A dumpsys deviceidle whitelist +$PKG

echo "Done. Open KDE Connect once so its background service starts."
