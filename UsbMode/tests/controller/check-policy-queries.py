#!/usr/bin/env python3
"""Keep the page wired to bounded batches and construction free of policy I/O."""
from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
src = root / 'src/org/andr36oid/usbmode'
functions = (src / 'UsbFunctions.java').read_text()
activity = (src / 'UsbDriveActivity.java').read_text()
control = (src / 'UsbDeviceControl.java').read_text()
native = (root / 'controller/main.cpp').read_text()
assert not re.search(r'for\s*\([^)]*[<=>]\s*(?:UsbFunctions\.)?ALL\b', functions)
assert not re.search(r'new String\s*\[\s*ALL', functions)
assert '.matches(' not in functions
assert '--policies' not in native + functions + control
assert 'return new UsbFunctions();' in re.search(r'static UsbFunctions policies\(\) \{(.*?)\n    }', control, re.S).group(1)
assert 'ProcessBuilder' not in control
assert 'policy.query(Collections.singleton(functions));' in control
refresh = activity[activity.index('private void refresh()'):activity.index('private void render()')]
assert 'mPolicy.query(' not in refresh and 'mPolicy.queryMissing(' not in refresh
render = activity[activity.index('private void render()'):activity.index('private void page(')]
assert render.count('queryPolicy(mFunctions);') == 1
query = activity[activity.index('private void queryPolicy('):activity.index('private void refresh()')]
assert query.index('work.executor.execute(') < query.index('policy.queryMissing(UsbFunctions.pageMasks(mask));')
assert 'mWork.policyFor == mask || mWork.querying' in query
assert 'current == UsbWizard.FUNCTIONS' in render and 'current == UsbWizard.REVIEW' in render
assert 'final int[] bits = UsbFunctions.BITS;' in activity
assert 'UsbFunctions.CONTROLLER_BITS' in activity
assert 'PreferenceScreen' not in activity and 'PreferenceFragment' not in activity
print('Wizard queries bounded cached policy batches only on relevant steps; polling performs no policy I/O')
