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

## Faster and lighter on slow phones
- **Texts sent from the desktop show up there even on phones that don't store them** (issue #3). KDE Connect isn't the default SMS app, so it can't write to the SMS database: storing a text it sends is up to Android, and the desktop shows a sent text only when the phone echoes it back from that database. Some phones don't store it, so the text reached the recipient but vanished from the desktop. The app now asks to be told when the text has gone out, looks for it in the database, and if the phone didn't store it, sends it to the desktop itself (in the right conversation). Phones that do store it are unchanged: the usual echo goes out and nothing extra is sent. (Such a text still isn't in the phone's own SMS app, so it isn't there when the desktop reloads the conversation later.)
- **Choose how many conversations go to the desktop** (issue #4). The SMS plugin's settings have "Conversations sent to the computer": All (the default, as upstream), newest 50 or newest 20. With a limit, each connect sends only the conversations with the newest messages, which is quicker on a slow phone with a long history. An older conversation still reaches the desktop as soon as a new message arrives in it, and the desktop can still open any conversation it already knows.
- **Picture messages can be left out** (issue #5). The SMS plugin's settings have "Picture messages (MMS) on the computer", on by default. Each MMS costs several extra database reads plus decoding its image into a thumbnail; switched off, MMS are skipped before any of that, in the conversation list, inside conversations and when new messages arrive, and only texts are synced. A conversation whose newest message is a picture then shows its newest text; one with only pictures isn't listed. Sending a picture from the desktop still works.
- **Text notifications reach the desktop right away.** On a slow phone the SMS app posts its notification seconds after a text arrives. The notification is now sent to the desktop straight from the incoming SMS, with the SMS app's name and icon and the contact's name, following the app filter, privacy and screen-off settings. When the SMS app's own notification arrives it updates that same desktop notification silently (adding Reply and its actions); another text from the same contact updates it too; if the SMS app never posts one, the early notification is taken back after 60 s.
- **SMS conversation list about 10x faster.** The newest message of every conversation comes from the message provider's own conversations query (content://mms-sms/conversations: one query, computed with the database's indexes, what Android's messaging app lists conversations with) instead of two queries per conversation. A conversation it doesn't give, or a phone whose provider doesn't answer it, falls back to one newest-first pass over the SMS and MMS tables and then to per-conversation queries. The list goes to the desktop 10 conversations per packet after the first, which still goes out on its own straight away.
- **Fewer database round trips.** Each query asks the phone's message database for only the rows it needs (LIMIT; if a phone refuses it, the query runs as before), the "does this phone have a SIM-ID column" check runs once instead of before every query, and the phone's own numbers are only looked up when an MMS needs them.
- **New texts keep reaching the computer after Android kills the app.** Whether the computer asked for messages is remembered, so a restarted app keeps pushing new texts.
- **Contacts about 2.8x faster, and they don't block anything else.** They used to be read on the connection's only reading thread, so while the computer synced contacts nothing else it sent (an SMS to send, a conversation to load) was even read. Now they are read in the background, built from one query of the contacts database instead of one vCard request per contact, and sent 50 at a time so the computer gets the first ones straight away. A contact the phone can't turn into a vCard no longer drops the others.
- **No Jetpack Compose.** Every screen is plain Android views, with the same look and keys. The app is smaller (7.4 MB to 6.1 MB with the change below) and its screens load less code: Run command opens in 2.6 s instead of 6.1 s on a slow emulator, Share in 3.4 s instead of 5.5 s.
- **Album art at screen size, without two libraries.** Album art is loaded with plain Android code instead of the Coil and OkHttp libraries, and decoded only as big as the screen (320 px on a 240×320 phone instead of 1024 px: a tenth of the memory per cover). Downloads still happen only on unmetered networks.
- **Notifications are built off the UI thread.** Decoding each app icon, PNG-encoding and hashing it and looking up the app name ran on the main thread for every notification; it now runs, in order, on a worker thread.
- **MMS photo thumbnails are decoded at thumbnail size.** A camera photo was decoded at full resolution (tens of MB of bitmap) just to make a 100 px thumbnail.
- **App start doesn't build the plugin list on the UI thread.**
- **Remote keyboard and mouse turn on without reconnecting.** Their accessibility service being switched on (setup re-run after an update, say) now loads them for the connected computer straight away.
- **Device screen rebuilt only when it changes.** A plugin loading or changing used to rebuild the whole screen; now it keeps its views when the change wouldn't alter what it shows (about 40% less layout and drawing at start).
- **Navigation drawer without Material's NavigationView.** NavigationView built every drawer row with a RecyclerView at each app start, even on keypad phones, where the drawer is never opened (Options has the same entries). A plain-view drawer with the same header, sections, icons and selected highlight builds its rows only when it is shown (straight away on tablets, where it is always visible).
- **Faster start: plain tiles, no BouncyCastle for the app's own certificate name.** The device screen's plugin tiles were MaterialCardViews, which cost far more to create and measure; they are plain views drawn exactly like Material 3 filled cards (same fill, 12dp corners, ripple). Every start also loaded BouncyCastle on the main thread just to read the name in the app's own certificate; plain names are now read from the certificate's own text (anything unusual still goes to BouncyCastle). Cold start median 4.8 s to 4.3 s over 10 runs on the slow emulator.
- **Less startup work per remembered device.** Each one used to cancel a pairing notification through the system at every start and on every device-screen refresh, though such a notification only exists after this process showed one; and each set up an IO thread pool on the main thread for pairing timers that a paired device never uses. Both now happen only when needed.
- **Keypad detection worked out once.** Whether the phone is a keypad phone took several system calls on every screen build, focus change and resume; it is now worked out once.
- **Find my phone** prepares its ringtone only when the phone is rung, instead of holding a media player for the whole connection.
- **Network discovery** (mDNS) runs for a minute after each network change or Refresh instead of all the time.

## Keypad-only phones (flip and candybar phones with no touchscreen)
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
- **Clear focus highlight.** The highlighted item gets Vela's orange outline. It hugs the control: a toggle's pill, a button's shape, a menu row. Settings choices that open a list (Convert to MMS, Theme, Conversations sent to the computer) ring the current option too.
- **Every screen opens with something already highlighted** (About opens on the app card), also right after the phone restarts. Android starts in touch mode until the first key press; the device screen then highlighted the whole page and its Options menu opened with nothing highlighted. Now the page never takes the highlight on keypad phones, and its controls and menu rows can be highlighted in touch mode too.
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
- **KDE Connect keyboard works on keypad phones.** Android never shows an on-screen keyboard while a hardware keypad is in use, so this keyboard used to drop every key from the computer. On keypad phones it now types whenever a text box is focused. (The accessibility version needs no keyboard switch and is the one set up by `keypad-phone-setup.sh`.)
- `keypad-phone-setup.sh` grants everything the app needs over USB, including the screens a keypad phone can't open: notification access, the accessibility service, battery exemption and permissions. It runs on the phone, so the two commands that start it work from Windows (Command Prompt or PowerShell), macOS and Linux alike, and it keeps other apps' accessibility services and notification listeners.

## Builds
- Every push that changes the app builds release APKs for **arm64-v8a** and **armeabi-v7a** and publishes them as a GitHub release (a pre-release on branches other than the default).
- Each build has its own version, 1.37.N, where N goes up with every build. A newer push cancels a build still running, and each release's notes list every commit since the previous release.
- Add `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` secrets to sign with your own key. Without them, builds are signed with the repo's debug key.

## Known limits on Android 7.1.1
- **Remote keyboard:**
  - Enter can't trigger a text box's own "Send" or "Search" button. That needs Android 11 or newer.
  - In unusual text boxes, such as those inside web pages or custom editors, typing may be less reliable than on newer Android.
- **Remote mouse:** drag and drop isn't live, as described above.
- Everything else behaves as upstream does on Android 7.
