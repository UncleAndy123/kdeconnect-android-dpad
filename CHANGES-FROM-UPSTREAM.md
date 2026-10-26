# Changes from upstream KDE Connect

Based on KDE Connect for Android 1.37.0 (upstream commit 46377f5, 25 Oct 2026).

## New app identity
- The app's package name is now **com.android.cts.kdeconnect** (was org.kde.kdeconnect_tp).
- It installs as a separate app and doesn't replace or conflict with the official KDE Connect.
- Debug and release builds use the same package name; debug builds no longer add ".debug".
- File sharing and the "don't forward our own notifications" check now follow the new name. Notifications from the official KDE Connect, if it's also installed, are still not forwarded.

## Works fully on Android 7.1.1 (and 6.x)
Upstream already ran on Android 6 and up, but some features were switched off or quietly broken on Android 6 and 7. They now work:

- **SMS / text messaging from the desktop.** Upstream hid this plugin on anything older than Android 8. It's enabled again on Android 6 and 7. This also fixes a crash when a conversation with a video MMS synced on Android 8.0 or older: those versions couldn't build the video preview image the way the app was doing it.
- **Remote keyboard (accessibility version).** Upstream hid this plugin on anything older than Android 8. It's now available on Android 6 and 7.
  - On Android 7 and older, typing into an empty text box no longer mixes in its grey placeholder text.
  - Enter used to do nothing on Android 10 and older. It now adds a new line in multi-line text boxes and otherwise taps the focused item.
  - **Keypad-phone navigation from the computer keyboard.** On Android 12 and older, the arrow keys used to move only the text cursor, and did nothing outside a text box. They now move the highlight between buttons and list items, like the phone's own D-pad. Inside a text box, left/right still move the cursor, and up/down jump out of single-line boxes.
  - With nothing highlighted, an arrow key now picks the first item.
  - Esc now acts as Back, except inside a text box, where it leaves the box.
- **Remote mouse: drag and drop.** On Android 7, holding the mouse button used to do only a long-press. You can now drag. Because of an Android 7 limit, the drag plays out on the phone when you let go of the button, not live while you move.
- **Receiving files.** Files sent from the computer keep their original "last modified" date on Android 7, as they already did on newer Android.

## Keypad-only phones (e.g. Kyocera Cadence S2720)
Everything below switches on automatically on phones with no touchscreen (or a real D-pad). Touch phones look and work exactly like upstream.

- **Soft keys instead of on-screen buttons.** This works the same way as Vela.
  - A slim bar at the bottom shows what the two soft keys do on each screen. The left key is usually **Options**, a menu of that screen's actions. The right key is the screen's main action:
    - **Refresh** on the device list
    - **Pair**, or **Accept**/**Reject**, on an unpaired device
    - **Play/Pause** in the media remote
    - **Type/Mouse** in the remote touchpad
    - **Send** when composing text
    - **Add** on the custom devices list
  - The on-screen buttons those keys replace (toolbars, floating buttons, Pair/Accept, Send, Play) are hidden to make room.
  - Back is the phone's own Back key.
  - Screens with nothing to put on the keys show no bar and keep their normal top bar.
  - The Options menu sits right on top of the bar and is only as wide as its longest entry.
  - Settings has a **Hardware soft keys** switch and **Calibrate soft keys**, for phones whose keys aren't recognised.
- **Clear focus highlight.** The highlighted item gets Vela's orange outline. It hugs the control: a toggle's pill, a button's shape, a menu row.
- **Every screen opens with something already highlighted.**
- **More room on tiny screens.** On 240×320 displays the app lays itself out as if the screen were wider, so things fit instead of being cut off. Text is a little smaller.
- **Everything reachable with the D-pad:**
  - Plugin rows now let you open a plugin's settings or turn it on/off.
  - Text boxes no longer trap the arrow keys.
  - The "device unreachable" screen has a Refresh button, since pull-to-refresh needs touch.
  - Custom devices can be deleted from their edit dialog, and IP addresses are easier to type on the keypad.
  - The volume mute button can be reached.
  - Seeking in the media remote works with left/right.
- **Smaller screens, fitted.** The media remote scrolls and drops the album art. The pairing screen scrolls so Accept/Reject are always reachable. Device cards and command output take less space.
- **Keypad mouse for the computer.** In the remote touchpad, the keypad becomes the mouse:
  - arrows or 2/4/6/8 move the pointer, speeding up while held
  - OK or 5 clicks, \* right-clicks, 3 middle-clicks, 9 double-clicks
  - 0 starts or ends a drag
  - 1/7 scroll
  - \# switches to typing on the computer; Options gets you back
- **T9 typing to the computer** works with multi-tap keyboards, and Backspace is passed through.
- **Presentation remote:** OK toggles the pointer. The pointer button is hidden on phones without a gyroscope.
- **Drawing tablet with the keypad.** On a phone without a touchscreen, the keypad moves a pen on the drawing pad: arrows or 2/4/6/8 move it (faster while held), OK or 5 puts the pen down or lifts it, \* switches between pen and eraser. The phone shows a preview of what you draw. It is on by default on keypad phones (upstream only enables it on tablets).
- **KDE Connect keyboard works on keypad phones.** Android never shows an on-screen keyboard while a hardware keypad is in use, so this keyboard used to drop every key from the computer. On keypad phones it now types whenever a text box is focused. (The accessibility version needs no keyboard switch and is the one set up by `cadence-phone-setup.sh`.)
- `cadence-phone-setup.sh` grants everything the app needs over USB, including the screens a keypad phone can't open: notification access, the accessibility service, battery exemption and permissions. It runs on the phone, so the two commands that start it work from Windows (Command Prompt or PowerShell), macOS and Linux alike, and it keeps other apps' accessibility services and notification listeners.

## Builds
- Every push that changes the app builds release APKs for **arm64-v8a** and **armeabi-v7a** and publishes them as a GitHub release (a pre-release on branches other than the default).
- Add `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` secrets to sign with your own key. Without them, builds are signed with the repo's debug key.

## Known limits on Android 7.1.1
- **Remote keyboard:**
  - Enter can't trigger a text box's own "Send" or "Search" button. That needs Android 11 or newer.
  - In unusual text boxes, such as those inside web pages or custom editors, typing may be less reliable than on newer Android.
- **Remote mouse:** drag and drop isn't live, as described above.
- Everything else behaves as upstream does on Android 7.
