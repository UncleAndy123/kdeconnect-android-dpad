# Target device matrix - D-pad + small-screen support

This fork's reason to exist is 100% D-pad operation of KDE Connect on real keypad phones with no
touchscreen. This folder tracks every phone we commit to supporting (the same list as
[vela-dpad](https://github.com/alltechdev/vela-dpad/tree/master/tests/devices), plus the Kyocera Cadence),
with the exact screen geometry to test against and the per-phone findings and screenshots.

**Scope caveat (read this):** all results here come from **SIMULATING** the screen (`wm size` /
`wm density`) on an emulator with no touchscreen, on the Android version the phone ships with -
**nothing has run on the actual phones**, so real-hardware quirks (odd soft-key codes, vendor ROM
changes) are unconfirmed. The soft keys are handled by [Yapchik](https://github.com/theOnionsAreWatching/yapchik),
which listens for every known soft-key code at once and has a press-your-keys calibration
(Options → Settings) for phones that use something else.

## Devices

| Model | Resolution | Density | Android tested | Frames |
|---|---|---|---|---|
| Kyocera Cadence S2720 | 240x320 | 160 | 7.1.1 | [`kyocera-cadence-s2720/`](kyocera-cadence-s2720/) (grid of every frame) |
| Kyocera e4810 | 240x320 | 160 | 11 | [`kyocera-e4810/`](kyocera-e4810/) (grid of every frame) |
| Kyocera DuraXV | 240x320 | 160 | 11 | not run yet (needs the Android 11 emulator) |
| TCL Flip 2 | 240x320 | 160 | 11 | not run yet (needs the Android 11 emulator) |
| Sonim XP3 (XP3800) | 240x320 | 160 | 11 | not run yet (needs the Android 11 emulator) |
| Kyocera DuraXe e4830 | 240x320 | **120** | 11 | not run yet (needs the Android 11 emulator) |
| Sonim X320 (XP3 Plus 5G) | 480x854 | 320 | 11 | not run yet (needs the Android 11 emulator) |
| Sonim X320 @ low density | 480x854 | **225** | 11 | not run yet (needs the Android 11 emulator) |

Every profile runs as its OWN row in the gate - no profile stands in for another, even at the same
geometry.

## The full-coverage gate

**`bash tests/devices/full_coverage.sh <id>`** (or `all`) drives EVERY keypad surface at the phone's
simulated geometry with keys only (D-pad, OK, soft keys, Back, number keys - never a tap), captures a
numbered screenshot of each into `<id>/screenshots/full/`, prints a COVERED / MISSED checklist, and
regenerates `<id>/README.md` (a grid of every frame plus the last result) with `make_grids.py`.
A surface is COVERED when it was reached by keys, something on it is focused (screens with no
controls, like "Device not paired", are allowed to have nothing focused), the focus is on screen,
and no text runs off the right edge.

**A phone is "fully supported" only when this reports FULLY COVERED (0 MISSED) AND every frame has been
checked by eye** (focus ring on the right control, nothing clipped, text readable). The checklist can't
see everything; the screenshots are the ground truth.

Surfaces (21): device list, list Options, Add devices by IP, Trusted networks, About, Device not paired,
device (after pairing), device Options, Plugin settings, media remote, PC volume, keypad mouse, mouse
Options, Compose text, Run Command, Run Command output, Find my phone, drawing tablet (pen moved and drawn
with 6 / 2 / OK), presentation remote, Settings, Share.

- **Phases - never run the full tour just to test one screen.** `PHASES="<names>" bash
  tests/devices/full_coverage.sh <id>` re-captures only those surface groups (same numbered frames,
  the rest left in place): `firstrun device media mouse runcommand findmyphone tablet presenter
  settings share`. A partial run reports PARTIAL, not a verdict. **Every new keypad screen adds its
  surfaces as its OWN phase in the same change.**
- **Every target geometry, every time.** A change to a screen is verified only when its phase has run
  green AND its frames were checked at every geometry in the matrix (240x320 @ 160 and @ 120,
  480x854 @ 320 and @ 225). One size never stands in for another.
- `DENS=<dpi>` overrides a profile's density (frames go to `screenshots/full-dens<dpi>/`).

### Running it

1. An emulator with no touchscreen and a D-pad, on the Android version in the table. For example an AVD
   from `system-images;android-30;default;x86_64` (Android 11) or `android-25` (7.1.1) with
   `hw.screen=no-touch`, `hw.dPad=yes`, `hw.keyboard=yes`, `hw.mainKeys=yes`. Install the app (debug or
   release) and run the phone setup from the main README once.
2. Start the stand-in desktop: `python3 tests/devices/standin_pc.py serve &`. It plays the KDE Connect
   desktop app (protocol 8, TLS, pairing accepted) and answers with sample data - a playing "Elisa"
   media player, two audio outputs, three Run Command commands - so every screen shows real content.
   It reaches the phone through `adb forward`, so it works with an emulator or a phone on USB.
3. `bash tests/devices/full_coverage.sh all`. The run clears the app's data first (it starts from a true
   first run and pairs with the stand-in), and resets `wm size` / `wm density` when it ends.

Keys: `adb shell input keyevent` starts a Java process per key (3-6 s on an emulator without hardware
acceleration), so on an emulator keys go in as raw key presses where possible - the same path a hardware
keypad takes. Android 7 images: everything through the emulator console. Android 8+ images (run
`adb root` first): arrows, Back and digits written straight to the emulator keyboard's `/dev/input`
node, while OK and the soft keys use `input keyevent` so they arrive as DPAD_CENTER / SOFT_LEFT /
SOFT_RIGHT. On a real phone every key uses `input keyevent`. Turn animations off on the emulator
(`settings put global window_animation_scale 0`, same for `transition_animation_scale` and
`animator_duration_scale`) and, on slow emulators, `settings put global hide_error_dialogs 1`.
On an emulator without hardware acceleration, also compile the app ahead of time after installing it
(`adb shell cmd package compile -m speed -f com.android.cts.kdeconnect`): run interpreted, its first start
takes ~50 s, past Android's 10 s foreground-service deadline, and Android kills it (an emulator artefact -
real phones start it in a second or two).

## STATUS

The live status of every row is the "Phone status" table in the main [README](../../README.md#phone-status),
updated with every push.
