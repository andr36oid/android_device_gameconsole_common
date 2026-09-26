# joyMouse

Mouse mode for the touchscreen-less RK3326 handhelds: the analog sticks drive a
real Android mouse pointer, with scrolling, clicks, drag and full button
remapping. Two parts:

* `joyMouse` (native daemon, `/system/bin/joyMouse`, started by `joyMouse.rc`)
* `JoyMouseSettings` (system app in `app/`): the *Settings › System › Joystick
  mouse* page and the toast shown on every mode switch

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

*Settings › System › Joystick mouse*:

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

## Building and testing

    m joyMouse JoyMouseSettings

Host unit tests (models, config, chord, and the controller against fake
devices):

    m joyMouse_tests && $ANDROID_HOST_OUT/nativetest64/joyMouse_tests/joyMouse_tests

The service runs as root without its own SELinux domain, like the previous
joyMouse and the su daemon, so it relies on the build's permissive SELinux.
