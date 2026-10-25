# KDE Connect for keypad phones (com.android.cts.kdeconnect)

A build of KDE Connect for Android that runs on **Android 7.1.1 phones with no touchscreen at all**, like the Kyocera Cadence S2720 flip phone. Every screen below was reached and used **with keys only**: the D-pad, OK, the two soft keys and Back. Nothing was tapped. The screenshots come from a 240×320, 160 dpi emulator with no touchscreen, the same as the Cadence.

The keypad experience follows [vela-dpad](https://github.com/alltechdev/vela-dpad):
- **Soft keys:** the left key is **Options**; the right key is the screen's main action. Back is the phone's Back key.
- **Focus:** an orange focus ring hugs the highlighted control, and every screen opens with something already highlighted.
- **Space:** on-screen buttons the soft keys replace are removed to save room.

Touch phones look exactly like upstream KDE Connect. See [CHANGES-FROM-UPSTREAM.md](CHANGES-FROM-UPSTREAM.md) for everything that changed, and run `cadence-adb-setup.sh` once to grant permissions a keypad phone can't open on its own.

<table>
  <tr><td align="center" width="25%"><img src="screenshots/keypad/01-device-list.png" width="200" alt="Device list"><br><b>Device list</b><br><sub>opens with the first device focused</sub><br><sub><code>(open app)</code></sub></td><td align="center" width="25%"><img src="screenshots/keypad/02-list-options.png" width="200" alt="Options menu"><br><b>Options menu</b><br><sub>sits on the soft-key bar</sub><br><sub><code>Left soft key</code></sub></td><td align="center" width="25%"><img src="screenshots/keypad/03-device.png" width="200" alt="Device"><br><b>Device</b><br><sub>first plugin focused</sub><br><sub><code>OK</code></sub></td><td align="center" width="25%"><img src="screenshots/keypad/04-device-options.png" width="200" alt="Device options"><br><b>Device options</b><br><sub>Ring, ping, plugin settings, unpair…</sub><br><sub><code>Left soft key</code></sub></td></tr>
  <tr><td align="center" width="25%"><img src="screenshots/keypad/05-media.png" width="200" alt="Media remote"><br><b>Media remote</b><br><sub>Play/Pause on the right key</sub><br><sub><code>→ OK</code></sub></td><td align="center" width="25%"><img src="screenshots/keypad/06-keypad-mouse.png" width="200" alt="Keypad mouse"><br><b>Keypad mouse</b><br><sub>the keypad drives the PC pointer</sub><br><sub><code>↓ OK</code></sub></td><td align="center" width="25%"><img src="screenshots/keypad/07-keypad-mouse-options.png" width="200" alt="Keypad mouse options"><br><b>Keypad mouse options</b><br><sub>keyboard, compose, settings</sub><br><sub><code>Left soft key</code></sub></td><td align="center" width="25%"><img src="screenshots/keypad/08-compose-text.png" width="200" alt="Compose text"><br><b>Compose text</b><br><sub>Clear / Send on the soft keys</sub><br><sub><code>Options → Compose text</code></sub></td></tr>
  <tr><td align="center" width="25%"><img src="screenshots/keypad/09-add-device.png" width="200" alt="Add device by IP"><br><b>Add device by IP</b><br><sub>dialog opens focused</sub><br><sub><code>Options → Add devices by IP → Add device</code></sub></td><td align="center" width="25%"><img src="screenshots/keypad/10-plugin-settings.png" width="200" alt="Plugin settings"><br><b>Plugin settings</b><br><sub>ring hugs the toggle</sub><br><sub><code>Options → Plugin settings</code></sub></td><td align="center" width="25%"><img src="screenshots/keypad/11-settings.png" width="200" alt="Settings"><br><b>Settings</b><br><sub>soft-key switch and calibration</sub><br><sub><code>Options → Settings</code></sub></td><td align="center" width="25%"><img src="screenshots/keypad/12-share.png" width="200" alt="Share to a device"><br><b>Share to a device</b><br><sub>first device focused</sub><br><sub><code>Share from any app</code></sub></td></tr>
</table>

Each caption shows the keys used to open that screen.

---

# KDE Connect - Android app

KDE Connect is a multi-platform app that allows your devices to communicate (eg: your phone and your computer).

## (Some) Features
- **Shared clipboard**: copy and paste between your phone and your computer (or any other device).
- **Notification sync**: Read and reply to your Android notifications from the desktop.
- **Share files and URLs** instantly from one device to another.
- **Multimedia remote control**: Use your phone as a remote for Linux media players.
- **Virtual touchpad**: Use your phone screen as your computer's touchpad and keyboard.

All this without wires, over the already existing Wi-Fi network, and using TLS encryption.

## About this app

This is a native Android port of the KDE Connect Qt app. You will find a more complete readme about KDE Connect [here](https://invent.kde.org/network/kdeconnect-kde/).

## How to install this app

You can install this app from the [Play Store](https://play.google.com/store/apps/details?id=org.kde.kdeconnect_tp) as well as [F-Droid](https://f-droid.org/repository/browse/?fdid=org.kde.kdeconnect_tp). Note you will also need to install the [desktop app](https://invent.kde.org/network/kdeconnect-kde) for it to work.

## Contributing

A lot of useful information, including how to get started working on KDE Connect and how to connect with the current developers, is on our [KDE Community Wiki page](https://community.kde.org/KDEConnect)

For bug reporting, please use [KDE's Bugzilla](https://bugs.kde.org). Please do not use the issue tracker in GitLab since we want to keep everything in one place.

To contribute patches, use [KDE Connect's Gitlab](https://invent.kde.org/network/kdeconnect-android/).
On Gitlab (as well as on our [old Phabricator](https://phabricator.kde.org/tag/kde_connect/)) you can find a task list with stuff to do and links to other relevant resources.
It is a good idea to also subscribe to the [KDE Connect mailing list](https://mail.kde.org/mailman/listinfo/kdeconnect).

Please know that all translations for all KDE apps are handled by the [localization team](https://l10n.kde.org/). If you would like to submit a translation, that should be done by working with the proper team for that language.

## License
[GNU GPL v2](https://www.gnu.org/licenses/gpl-2.0.html) and [GNU GPL v3](https://www.gnu.org/licenses/gpl-3.0.html)

If you are reading this from GitHub, you should know that this is just a mirror of the [KDE Project repo](https://invent.kde.org/network/kdeconnect-android/).
