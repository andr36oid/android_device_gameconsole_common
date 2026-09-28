# joyMouse

Mouse mode for the touchscreen-less RK3326 handhelds: the analog sticks drive a
real Android mouse pointer, with scrolling, clicks, drag and full button
remapping. The same daemon also runs **touch controls**: per-app layouts that
turn buttons and sticks into touches for touchscreen-only games (see below).
Parts:

* `joyMouse` (native daemon, `/system/bin/joyMouse`, started by `joyMouse.rc`)
* `JoyMouseSettings` (system app in `app/`): the *Settings › Joystick
  mouse* page and the toast shown on every mode switch
* `TouchMapper` (system app in `../TouchMapper`): *Settings › Touch controls*,
  the on-screen editor, the hints, and the watcher that names the app in front

## Using it

Hold **L3 + R3** (both sticks pressed, nothing else) for **1 second** to switch
mouse mode on or off. A toast confirms the switch and lists the controls; the
pointer appears in the middle of the screen when mouse mode turns on and
disappears when it turns off.

Default controls in mouse mode:

| Input | Does |
|---|---|
| Right stick | Moves the pointer |
| Left stick | Scrolls (vertical and horizontal) |
| A | Left click while the pointer is shown; after d-pad navigation it confirms the focused item instead |
| R1 | Left click (hold to drag or long-press) |
| R3 (tap) | Left click, without the stick press moving the pointer; hold it to drag |
| L1 | Right click |
| R2 (hold) | Precision: slow pointer |
| L2 (hold) | The pointer stick scrolls instead |
| Everything else | Unchanged: B is back, d-pad navigates, X/Y/Start/Select as usual |

Mouse mode can also be switched from the Settings page, and with
`setprop sys.joymouse.active 1` / `0`.

### Not by accident

The combination has to be held for the whole hold time (1 s by default, up to
10 s), and every button of it released since the last toggle. Like the original
joyMouse, nothing else is checked.

While a fullscreen app, usually a game, is on screen (SystemUI sets
`sys.joymouse.fullscreen` while the status bar is hidden), it is stricter:

* exactly the combination is held, nothing else,
* and no stick is pushed past 90 % of its range (pressing L3 + R3 while
  steering is gameplay, not a toggle). The R36S sticks are small and tilt when
  pressed, so this stays high.

### Button mapping

In mouse mode the pad is grabbed, so the framework's hardware button remap
(Settings › Button mapping) can't apply to what joyMouse handles itself. The
framework copies the remap to `/data/system/hardware_button_remap` and changes
`sys.hardware_button_remap.serial`; joyMouse then gives each button the action
of the button it acts as. The toggle combination always uses the physical
buttons.

The combination needs at least two buttons, can be changed, and can be switched
off altogether (then only Settings or the property switch it).

## Settings

*Settings › Joystick mouse*:

* **Mouse mode** on/off
* **Switching**: toggle combination on/off, which buttons, hold time, start in
  mouse mode after boot, on-screen message
* **Pointer**: speed, fine control (response curve), dead zone, precision
  speed, which stick is the pointer, invert horizontal / vertical
* **Scrolling**: speed, natural scrolling
* **Button mapping**: an action for every button (A, B, X, Y, L1, R1, L2, R2,
  L3, R3, Select, Start, Fn, d-pad): normal button, nothing, left / right /
  middle click, back, forward, smart click, precision, scroll
* **Current controls** summary and **Reset to defaults**

Changes apply immediately, also while mouse mode is on. Android's own
*Pointer speed* setting still scales the result.

## Properties

Everything the page sets is a system property, so it also works from adb.

| Property | Default | Meaning |
|---|---|---|
| `sys.joymouse.active` | | `1`/`0`: mouse mode state; set it to switch |
| `persist.sys.joymouse.classic` | `0` | Classic mode: the pad isn't grabbed, the pointer stick moves the pointer and its click is a left click, games still get every button and the other stick |
| `persist.sys.joymouse.speed` | `100` | Top pointer speed, % (100 % = 0.9 screen diagonals per second) |
| `persist.sys.joymouse.curve` | `2.2` | Response exponent, `1` = linear |
| `persist.sys.joymouse.deadzone` | `10` | Pointer stick dead zone, % |
| `persist.sys.joymouse.precision` | `35` | Speed while Precision is held, % |
| `persist.sys.joymouse.pointer_stick` | `right` | `left` swaps the sticks |
| `persist.sys.joymouse.pointer_invert_x` / `_y` | `0` | Invert pointer movement |
| `persist.sys.joymouse.scroll_speed` | `100` | Scroll speed, % |
| `persist.sys.joymouse.scroll_deadzone` | `20` | Scroll stick dead zone, % |
| `persist.sys.joymouse.natural_scroll` | `0` | Content follows the stick |
| `persist.sys.joymouse.toggle_enabled` | `1` | Toggle combination on/off |
| `persist.sys.joymouse.toggle` | `L3+R3` | Toggle combination, 2+ buttons joined by `+` |
| `persist.sys.joymouse.toggle_ms` | `1000` | Hold time |
| `persist.sys.joymouse.start_active` | `0` | Start in mouse mode after boot |
| `persist.sys.joymouse.toast` | `1` | Toast on every switch |
| `persist.sys.joymouse.btn_<button>` | see above | Action per button: `pass`, `none`, `left`, `right`, `middle`, `back`, `forward`, `smart`, `precision`, `scroll`. Buttons: `a b x y l1 r1 l2 r2 l3 r3 select start mode up down left right` |
| `persist.sys.joymouse.invert_lx` / `ly` / `rx` / `ry` | device | Hardware axis correction (`ry` defaults to inverted on everything but the R36S) |
| `persist.sys.joymouse.accel_comp` | `1` | Compensate Android's pointer acceleration |
| `persist.sys.joymouse.rate` | `250` | Output rate, Hz |
| `persist.sys.joymouse.click_freeze_ms` | `60` | Pointer hold after a click |
| `persist.sys.joymouse.device` | | Event node or name substring of the pad, if auto-detection picks the wrong one |
| `persist.sys.joymouse.display` | | `WxH` if the panel size can't be read from sysfs |
| `persist.sys.joymouse.debug` | `0` | Verbose logcat (`logcat -s joyMouse`) |

## How it works

* The daemon picks the built-in pad among `/dev/input/event*` (two sticks,
  gamepad buttons, not virtual) and follows hotplug via inotify.
* Mouse mode off: it only watches buttons for the toggle; stick events are
  filtered in the kernel (`EVIOCSMASK`), so games cost it no wakeups.
* Mouse mode on: it creates a uinput mouse and a copy of the pad (same name and
  IDs, so the same key layout applies), then grabs the real pad once nothing is
  held. Mouse inputs go to the mouse, everything else is replayed through the
  copy, so Android never gets a click and a button press for the same input.
  Leaving removes both devices, which also removes the pointer.
* Pointer: radial dead zone, per-direction learned stick travel, a power curve
  from a small floor speed up to the top speed, asymmetric smoothing (40 ms
  speeding up, 15 ms slowing down, instant stop on release), sub-pixel
  accumulation, 250 Hz output, and the inverse of Android 11's pointer
  acceleration so the configured speeds are what ends up on screen. Top speed
  scales with the panel diagonal.
* Scrolling: wheel notches at 3–14 per second depending on deflection, the
  first one immediately, dominant axis only.
* Toasts: on each switch the daemon runs `cmd activity broadcast` to
  `JoyMouseSettings` (permission-protected receiver), which shows the toast.

`src/Controller.cpp` holds the whole mode/button/motion state machine without
touching a device node; the Linux side is `EvdevPad`, `Uinput`, `Display`,
`Properties`, `Announcer` and `main.cpp`.

## Touch controls

Each app can have a layout (a profile) that turns buttons and sticks into
touches on a virtual touchscreen. It switches on by itself while that app is in
front; apps without one keep the normal buttons.

* **Editor**: hold FN and press L1 in the game (PhoneWindowManager sends
  `org.andr36oid.touchmapper.action.EDIT`), or *Settings › Touch controls ›
  Add an app / Edit the layout*. It opens on top of the game and works with
  the buttons alone.
* **Kinds of control**: tap, hold, joystick (a stick or the d-pad drags a finger
  around a point), look around (a stick drags a finger across an area, lifts
  and starts over from the middle at the edge), swipe (a quick swipe in one
  direction). A shift button (held) switches to a second set; anything the
  second set doesn't define keeps its first-set control.
* **What stays a button**: everything the profile doesn't use goes on to
  Android through a copy of the pad, like in mouse mode. FN, and any button
  pressed while FN is held, always goes through, so Home and all FN shortcuts
  keep working. Volume and power are separate devices and never grabbed.
* **Mouse mode wins**: while mouse mode is on, touch mode steps back, and comes
  back when mouse mode ends. Only one of them drives the pad at a time
  (`SharedPad` also keeps the one evdev grab between them).

### How it fits together

    TouchMapper app (system uid)                 joyMouse daemon (root)
    ----------------------------                 ----------------------
    WatcherService: task stack listener          PropertyWatcher -> loadTouch()
      app in front has an enabled profile  --->  sys.touchmap.profile=<package>
      profile saved                        --->  sys.touchmap.serial=<time>
      display size / rotation              --->  sys.touchmap.display=640x480@0
    EditorActivity writes                        reads
      /data/system/andr36oid/touchmap/<package>.json
                                                 TouchController: grab pad,
                                                 uinput touchscreen + pad copy

The virtual touchscreen is `andr36oid Touch Controls` (BUS_VIRTUAL, vendor and
product 0, `INPUT_PROP_DIRECT`, 10 slots, `ABS_MT_SLOT` / `ABS_MT_TRACKING_ID` /
`ABS_MT_POSITION_X/Y`, `BTN_TOUCH`). Its raw range is the display in its
natural orientation; `andr36oid_Touch_Controls.idc` makes it an internal
touchscreen on the built-in display, and Android rotates its touches with the
display. The build doesn't declare `android.hardware.touchscreen`, so the
device appearing or going away doesn't change the apps' configuration (no
activity restarts).

### Profile format

    {
      "version": 1,
      "package": "com.example.game",
      "enabled": true,          // off: the app keeps normal buttons
      "hints": true,            // faint labels on top of the game
      "shift": "L2",            // or "none"
      "deadzone": 15,           // stick dead zone, %
      "controls": [
        {"type": "tap",      "button": "A",     "x": 0.88, "y": 0.80, "radius": 0.06},
        {"type": "hold",     "button": "R1",    "x": 0.90, "y": 0.45, "radius": 0.06},
        {"type": "joystick", "stick": "left",   "x": 0.17, "y": 0.72, "radius": 0.14},
        {"type": "camera",   "stick": "right",  "x": 0.65, "y": 0.45, "radius": 0.25, "speed": 1.0},
        {"type": "swipe",    "button": "Y",     "x": 0.50, "y": 0.60, "angle": 270, "length": 0.2},
        {"type": "joystick", "stick": "dpad",   "x": 0.17, "y": 0.72, "radius": 0.14, "layer": 1}
      ]
    }

The comments are only here; the files are plain JSON.

* `x`, `y`: share of the screen's width and height, as the app is shown.
* `radius`, `length`: share of the shorter screen side. Joystick: how far the
  finger goes at full tilt. Camera: the area. Tap and hold: marker size only.
* `speed` (camera): 1 = one shorter screen side per second at full tilt, with
  a 1.6 response curve.
* `angle` (swipe): degrees, 0 right, 90 down, 180 left, 270 up.
* `layer`: 1 = only while the shift button is held.
* Buttons: `A B X Y L1 R1 L2 R2 L3 R3 SELECT START UP DOWN LEFT RIGHT`
  (physical buttons, the hardware button remap doesn't apply). Sticks:
  `left right dpad`. A d-pad joystick takes the four d-pad buttons of its layer.

Controls that can't work (unknown kind, no button, a button twice on one
layer, the shift button as a control) are left out with a warning in logcat.

Timing: taps last 50 ms, swipes 120 ms, a joystick finger rests on its center
for 24 ms before it follows the stick, a camera finger lifts 150 ms after the
stick is let go. Fingers move at 125 Hz, only while something moves.

## Building and testing

    m joyMouse JoyMouseSettings

Host unit tests (models, config, chord, the mouse controller and touch mode
against fake devices, touch slots, profile parsing, rotation):

    m joyMouse_tests && $ANDROID_HOST_OUT/nativetest64/joyMouse_tests/joyMouse_tests

The service runs as root without its own SELinux domain, like the previous
joyMouse and the su daemon, so it relies on the build's permissive SELinux.
