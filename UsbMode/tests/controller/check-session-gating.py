#!/usr/bin/env python3
"""The session may transport pad frames without opening the keyboard UI channel."""
from pathlib import Path

root = Path(__file__).resolve().parents[2]
src = root / 'src/org/andr36oid/usbmode'
session = (src / 'ControllerSessionActivity.java').read_text()
update = session.split('private void updateKeyboard() {', 1)[1].split('private int dialogFrame', 1)[0]
keyboard = update.split('new KeyboardChannel', 1)[0]
assert '(mMask & UsbFunctions.KEYBOARD) != 0 && UsbDeviceControl.keyboardScreen()' in keyboard
assert 'else' not in keyboard
assert 'new ControllerInputChannel()' in update
assert 'mModel.update' not in (src / 'ControllerInputChannel.java').read_text()
assert 'setFunctions(mMask, canChangeDisc()' in session
assert '"cdrom"' in session.split('mKeyboardModel.setFunctions', 1)[1].split('configurePads()', 1)[0]
native = (root / 'controller/main.cpp').read_text()
assert 'typing && controls.keyboardScreen() ? "keyboard" : "gamepad"' in native
assert 'set("routing", "");' in native
helper = (root / 'andr36oid-usb-drive.sh').read_text().split('cleanup() {', 1)[1].split('prepare_state()', 1)[0]
assert 'setprop "$P.routing" \'\'' in helper
assert 'setprop "$P.active_functions" 0' in helper
service = (src / 'UsbModeService.java').read_text()
destroy = service.split('public void onDestroy() {', 1)[1].split('public IBinder', 1)[0]
assert 'if (!mIdleStop) UsbDriveControl.stop();' in destroy
idle = service.split('else if (!device && !UsbDriveControl.busy()) {', 1)[1].split('private void createChannels()', 1)[0]
assert 'UsbDriveControl.stop();' not in idle
assert idle.index('mIdleStop = true;') < idle.index('stopSelf();')
print('Session keyboard, disc action and stop routing gates passed')
