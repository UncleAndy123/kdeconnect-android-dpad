# Changes from upstream KDE Connect

Based on KDE Connect for Android 1.37.0 (upstream commit 46377f5, 25 Oct 2026).

## New app identity
- The app's package name is now **com.android.cts.kdeconnect** (was org.kde.kdeconnect_tp).
- It installs as a separate app and doesn't replace or conflict with the official KDE Connect.
- Debug and release builds use the same package name; debug builds no longer add ".debug".
- File sharing, home-screen shortcuts and the "don't forward our own notifications" check were updated to follow the new name.

## Works fully on Android 7.1.1
Upstream already ran on Android 6 and up, but some features were switched off or quietly broken on Android 7.x. They now work:

- **SMS / text messaging from the desktop.** Upstream hid this plugin on anything older than Android 8. It's enabled again on Android 7. This also fixes a crash when a conversation with a video MMS synced: older Android couldn't build the video preview image the way the app was doing it.
- **Remote keyboard (accessibility version).** Upstream hid this plugin on anything older than Android 8. It's now available on Android 7.
  - Enter used to do nothing on Android 10 and older. It now adds a new line in multi-line text boxes and otherwise taps the focused item.
  - **Keypad-phone navigation from the computer keyboard.** On Android 12 and older, the arrow keys used to move only the text cursor, and did nothing outside a text box. They now move the highlight between buttons and list items, like the phone's own D-pad. Inside a text box, left/right still move the cursor, and up/down jump out of single-line boxes.
  - With nothing highlighted, an arrow key now picks the first item.
  - Esc now acts as Back, except inside a text box, where it leaves the box.
- **Remote mouse: drag and drop.** On Android 7, holding the mouse button used to do only a long-press. You can now drag. Because of an Android 7 limit, the drag plays out on the phone when you let go of the button, not live while you move.
- **Receiving files.** Files sent from the computer keep their original "last modified" date on Android 7, as they already did on newer Android.

## Known limits on Android 7.1.1
- **Remote keyboard:**
  - Enter can't trigger a text box's own "Send" or "Search" button. That needs Android 11 or newer.
  - In unusual text boxes, such as those inside web pages or custom editors, typing may be less reliable than on newer Android.
- **Remote mouse:** drag and drop isn't live, as described above.
- Everything else behaves as upstream does on Android 7.
