#!/usr/bin/env python3
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2] / 'res'
english = {node.attrib.get('name'): node.text or '' for node in ET.parse(root / 'values/strings.xml').getroot()}
keys = [name for name in english if name.startswith(('usb_ds4_', 'usb_jiggle_', 'usb_macro_', 'usb_link_', 'usb_stream_', 'usb_sound_', 'usb_disc_', 'usb_floppy_'))]
assert len(keys) == 44
for locale in ('de','es','fr','in','ja','pl','pt','pt-rBR','ru','tr','zh-rCN'):
    path = root / ('values-' + locale) / 'strings.xml'
    translated = {node.attrib.get('name'): node.text or '' for node in ET.parse(path).getroot()}
    for key in keys:
        assert key in translated and translated[key], (locale,key)
        assert re.findall(r'%\d+\$[ds]',english[key]) == re.findall(r'%\d+\$[ds]',translated[key]), (locale,key)
        assert '\\\\n' not in translated[key], (locale,key,'literal newline escape')
    assert len(translated['usb_jiggle_times'].split('|')) == 4
    assert len(translated['usb_macro_media'].split('|')) == 6
    assert len(translated['usb_sound_examples'].split('|')) == 12
print('44 strings in all 11 requested locales, format arguments and pad/menu counts passed')
