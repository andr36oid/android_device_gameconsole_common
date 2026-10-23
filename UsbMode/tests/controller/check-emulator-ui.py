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
assert 'setShowSoftInputOnFocus(false)' not in activity
assert 'final String focus = same' in activity and ': same ? mFocus : ""' in activity
assert 'mTextScroll.setOnFocusChangeListener' not in activity
assert 'mShownPage = -1; mShownStep = -1;' in activity
assert 'mPageFocus.ready(version, mResumed, hasWindowFocus(), mDialog != null)' in activity
assert 'UsbWizard.captured(stage,' in activity
assert ET.parse(root / 'res/layout/usb_drive_activity.xml').find(".//ScrollView[@{http://schemas.android.com/apk/res/android}id='@+id/text_scroll']").get('{http://schemas.android.com/apk/res/android}focusable') == 'false'
assert 'new RadioButton(this)' in activity and 'R.drawable.usb_choice_background' in activity
assert 'VirtualDrive.presets(mImages.free, mImages.filesystem)' in activity
assert 'option.setChecked(VirtualDrive.presetSelected(mImageSize,' in activity
assert 'VirtualDrive.clampSize(value, mImages.free, mImages.filesystem)' in activity
assert 'VirtualDrive.defaultLabel(mImageName, mImageFs)' in activity
assert 'UsbWizard.bootMenu(mFunctions, mSelected, mCdrom)' in activity
assert 'DeviceModeWarning.show(' not in activity
assert 'mCreatedName' in activity and 'beginSession(image, false, false)' in activity
session = ET.parse(root / 'res/layout/usb_device_session.xml')
assert not any(e.tag in ('Button', 'SeekBar') for e in session.iter())
manifest = ET.parse(root / 'AndroidManifest.xml')
android = '{http://schemas.android.com/apk/res/android}'
for e in manifest.iter('activity'):
    if e.get(android + 'name') == '.ControllerSessionActivity':
        assert e.get(android + 'theme') == '@style/Andr36oidSetupTheme'
print('Emulator cards, focus, system IME, size indicators, labels, boot gating and status-only session guards passed')
