#!/usr/bin/env python3
"""Keep the page wired to bounded batches and construction free of policy I/O."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
src = root / 'src/org/andr36oid/usbmode'
functions = (src / 'UsbFunctions.java').read_text()
fragment = (src / 'UsbDriveFragment.java').read_text()
control = (src / 'UsbDeviceControl.java').read_text()
native = (root / 'controller/main.cpp').read_text()
assert not re.search(r'for\s*\([^)]*[<=>]\s*(?:UsbFunctions\.)?ALL\b', functions)
assert not re.search(r'new String\s*\[\s*ALL', functions)
assert '.matches(' not in functions
assert '--policies' not in native + functions + control
assert 'return new UsbFunctions();' in re.search(r'static UsbFunctions policies\(\) \{(.*?)\n    }', control, re.S).group(1)
assert 'ProcessBuilder' not in control
assert 'policy.query(Collections.singleton(functions));' in control
refresh = fragment[fragment.index('private void refresh()'):fragment.index('@Override public void onRequestPermissionsResult')]
assert refresh.count('mPolicy.query(masks);') == 1
assert refresh.index('UsbFunctions.pageMasks(mFunctions)') < refresh.index('mPolicy.query(masks);') < refresh.index('mPolicy.reason(')
assert 'final int[] bits = UsbFunctions.BITS;' in refresh
assert 'final int[] bits = UsbFunctions.CONTROLLER_BITS;' in fragment
assert fragment.count('mPolicy.queryMissing(UsbFunctions.pageMasks(mFunctions));') == 2
print('Device page uses one bounded policy batch, shared offered masks and cache-only reads')
