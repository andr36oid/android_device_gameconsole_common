#!/usr/bin/env python3
"""Guard the on-device regressions at the Android UI/model boundary."""
from pathlib import Path
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
src = root / 'src/org/andr36oid/usbmode'
activity = (src / 'UsbDriveActivity.java').read_text()
assert not (src / 'GamepadTextDialog.java').exists()
assert 'GamepadTextDialog' not in ''.join(p.read_text() for p in src.glob('*.java'))
assert 'setShowSoftInputOnFocus(true)' in (src / 'UsbTextInput.java').read_text()
assert 'field.post(() -> show(field' in (src / 'UsbTextInput.java').read_text()
assert 'showSoftInput(field' in (src / 'UsbTextInput.java').read_text()
assert 'showSoftInput(field, InputMethodManager.SHOW_FORCED)' in (src / 'UsbTextInput.java').read_text()
assert 'SHOW_IMPLICIT' not in (src / 'UsbTextInput.java').read_text()
assert 'setShowSoftInputOnFocus(false)' not in activity
keys = activity.split('public boolean dispatchKeyEvent(KeyEvent event) {', 1)[1].split('private int step()', 1)[0]
assert keys.index('KeyEvent.FLAG_SOFT_KEYBOARD') < keys.index('KeyEvent.KEYCODE_DPAD_DOWN')
assert keys.index('UsbTextInput.activate(') < keys.index('return super.dispatchKeyEvent(new KeyEvent(')
assert keys.index('UsbTextInput.activate(') < keys.index('KeyEvent.KEYCODE_DPAD_DOWN')
assert 'field.setOnKeyListener((v, key, event) -> activate(field, key, event));' in (src / 'UsbTextInput.java').read_text()
dialogs = (src / 'UsbDialogInput.java').read_text()
assert 'dialog.getCurrentFocus() instanceof EditText && UsbTextInput.activate(' in dialogs
assert 'UsbDialogInput.show(dialog);' in activity
ask = activity.split('private void ask(', 1)[1].split('private String name(', 1)[0]
assert 'showDialog(dialog);' in ask and 'dialog.show();' not in ask
macros = (src / 'MacroSettings.java').read_text()
assert macros.count('UsbDialogInput.show(') == 5
assert '.show();' not in macros
assert 'saveEntry(); UsbTextInput.hide(this); super.onPause();' in activity
assert 'onViewDetachedFromWindow(View view) { hide(field); }' in (src / 'UsbTextInput.java').read_text()
assert 'else { UsbTextInput.hide(editor); if (numeric) saveEntry(); }' in activity
assert 'final int textY = same ? mTextScroll.getScrollY() : 0;' in activity
assert 'final String focus = same' in activity and ': same ? mFocus : ""' in activity
assert 'mTextScroll.setOnFocusChangeListener' not in activity
assert 'mShownPage = -1; mShownStep = -1;' in activity
assert 'mPageFocus.ready(version, mResumed, hasWindowFocus(), mDialog != null)' in activity
assert 'UsbWizard.captured(stage,' in activity
assert ET.parse(root / 'res/layout/usb_drive_activity.xml').find(".//ScrollView[@{http://schemas.android.com/apk/res/android}id='@+id/text_scroll']").get('{http://schemas.android.com/apk/res/android}focusable') == 'false'
assert 'new RadioButton(this)' in activity and 'R.drawable.usb_choice_background' in activity
assert 'VirtualDrive.presets(mImages.free, mImages.filesystem)' in activity
assert 'option.setChecked(VirtualDrive.presetSelected(mImageSize,' in activity
assert 'VirtualDrive.sameCard(mFormCid, mImages)' in activity
assert 'VirtualDrive.sameCard(images.cid, fresh)' in activity
assert 'option.setVisibility(VirtualDrive.size(size) <= VirtualDrive.maximum(mImages.free, mImages.filesystem)' in activity
assert 'if (mEdit != null && mEdit.isFocused() && mDialog == null)' in activity
assert 'VirtualDrive.clampSize(value, mImages.free, mImages.filesystem)' in activity
assert 'VirtualDrive.defaultLabel(mImageName, mImageFs)' in activity
assert 'UsbWizard.bootMenu(mFunctions, mSelected, mCdrom)' in activity
assert 'DeviceModeWarning.show(' not in activity
review = activity.split('private void review() {', 1)[1].split('private String startReason()', 1)[0]
assert 'if (!mWork.wifi.isEmpty()) body += "\\n\\n" + mWork.wifi;' in review
assert 'if (pair[0] == 0 || pair[1] == 0)' in activity
hub = activity.split('private void hub() {', 1)[1].split('private void liveResult()', 1)[0]
assert 'mBack.setEnabled(mWork.loaded)' in hub
assert 'mWork.creation.matches(image)' in activity and 'if (!beginSession(image, false, false)) return;' in activity
assert 'UsbRequest.executor.execute(' in activity
assert 'mWork.request.cancel(); mWork.creation.waiting = false;' in activity
assert activity.index('if (UsbDriveControl.busy()) {', activity.index('private void refresh()')) < activity.index('if (mWork.creation.waiting) {', activity.index('private void refresh()'))
session = ET.parse(root / 'res/layout/usb_device_session.xml')
assert not any(e.tag in ('Button', 'SeekBar') for e in session.iter())
manifest = ET.parse(root / 'AndroidManifest.xml')
android = '{http://schemas.android.com/apk/res/android}'
for e in manifest.iter('activity'):
    if e.get(android + 'name') == '.ControllerSessionActivity':
        assert e.get(android + 'theme') == '@style/Andr36oidSetupTheme'
print('Emulator cards, focus, system IME, size indicators, labels, boot gating and status-only session guards passed')
