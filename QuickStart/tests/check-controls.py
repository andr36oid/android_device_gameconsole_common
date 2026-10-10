#!/usr/bin/env python3
"""Host check for Settings > Buttons and shortcuts (QuickStart/res/raw/controls.txt).

Every row on the page must match the build, and every combination the build has must be on
the page. Compared with:
  - the key layout and key character map of the built-in pad (Vendor_484b_Product_1100.kl/kcm)
  - the FN shortcuts in PhoneWindowManager (frameworks/base)
  - the seeded RetroArch hotkeys (emulators/seed/retroarch/retroarch.cfg)
  - the seeded PPSSPP, Flycast and Mupen64Plus AE maps, when this tree has them
  - joyMouse's default mouse mode buttons
  - the strings: every name in the file exists, every translation of controls_strings.xml
    has exactly the English names

Roots for "check has/lacks/exists <root>:<path>":
  common  this repository (default: two folders up from this script)
  fwb     frameworks/base   (--fwb, env FWB, default /andr36oid/src/frameworks/base)
  r36s    device/gameconsole/r36s (--r36s, env R36S, default /andr36oid/src/device/gameconsole/r36s)
A missing fwb or r36s tree (e.g. in CI without the Android tree) skips those checks with a
note. A seeded file that is missing from this tree only skips the rows that are hidden
without it (if-file); otherwise it is an error.

Check kinds (lines "  check ..." under a row or text in controls.txt):
  kl KEY [SCAN] ...       the key is in the .kl (at that scan code)
  kcm KEY FALLBACK        the .kcm gives KEY that fallback
  fn                      each "FN + K" is an FN shortcut in PhoneWindowManager
  ra ACTION...            Select is RetroArch's hotkey button and "Select + K" is
                          input_ACTION_btn (one ACTION per alternative)
  ra-combo                the row's buttons are input_menu_toggle_gamepad_combo
  ppsspp NAME[|NAME]      controls.ini NAME binds the row's buttons
  flycast ACTION[|ACTION] dreamcast.cfg [combo] binds Select + K to ACTION
  mupen COMMAND           the Mupen profile maps COMMAND to the row's button
  mouse ACTION...         joyMouse's default action of each alternative
  has / lacks ROOT:PATH REGEX, exists ROOT:PATH
  hardware REASON         nothing to compare with (listed in the output)

Exit status 0 when everything matches. Run: python3 check-controls.py [-v]
"""

import argparse
import os
import re
import sys
import xml.dom.minidom

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.dirname(HERE)

KL = 'Vendor_484b_Product_1100.kl'
KCM = 'Vendor_484b_Product_1100.kcm'
PWM = 'services/core/java/com/android/server/policy/PhoneWindowManager.java'
RA_CFG = 'emulators/seed/retroarch/retroarch.cfg'
PPSSPP = 'emulators/seed/ppsspp/controls.ini'
FLYCAST = 'foss-apps/Flycast/firstrun/flycast/dreamcast.cfg'
MUPEN = 'foss-apps/Mupen64PlusAE/firstrun/mupen64plusae/controller.cfg'
MOUSE = 'joyMouse/src/Buttons.cpp'

# Button names on the page -> Android key code name. @name labels come from the resources.
LABELS = {
    'FN': 'HOME', 'A': 'BUTTON_A', 'B': 'BUTTON_B', 'X': 'BUTTON_X', 'Y': 'BUTTON_Y',
    'L1': 'BUTTON_L1', 'R1': 'BUTTON_R1', 'L2': 'BUTTON_L2', 'R2': 'BUTTON_R2',
    'L3': 'BUTTON_THUMBL', 'R3': 'BUTTON_THUMBR', 'Start': 'BUTTON_START',
    'Select': 'BUTTON_SELECT', '↑': 'DPAD_UP', '↓': 'DPAD_DOWN', '←': 'DPAD_LEFT',
    '→': 'DPAD_RIGHT', '@key_volume+': 'VOLUME_UP', '@key_volume-': 'VOLUME_DOWN',
    '@key_power': 'POWER', '@key_dpad': 'DPAD_UP',
    '@key_left_stick': None, '@key_right_stick': None,
}
# Android key codes (android.view.KeyEvent) of the keys used above
KEYCODES = {
    'DPAD_UP': 19, 'DPAD_DOWN': 20, 'DPAD_LEFT': 21, 'DPAD_RIGHT': 22, 'VOLUME_UP': 24,
    'VOLUME_DOWN': 25, 'POWER': 26, 'HOME': 3, 'BUTTON_A': 96, 'BUTTON_B': 97, 'BUTTON_X': 99,
    'BUTTON_Y': 100, 'BUTTON_L1': 102, 'BUTTON_R1': 103, 'BUTTON_L2': 104, 'BUTTON_R2': 105,
    'BUTTON_THUMBL': 106, 'BUTTON_THUMBR': 107, 'BUTTON_START': 108, 'BUTTON_SELECT': 109,
}
# joyMouse Button enum names
MOUSE_BUTTONS = {
    'A': 'A', 'B': 'B', 'X': 'X', 'Y': 'Y', 'L1': 'L1', 'R1': 'R1', 'L2': 'L2', 'R2': 'R2',
    'L3': 'L3', 'R3': 'R3', 'Select': 'Select', 'Start': 'Start',
}
# RetroArch input_menu_toggle_gamepad_combo (input/input_defines.h, enum input_combo_type)
RA_COMBOS = {
    1: {'↓', 'Y', 'L1', 'R1'}, 2: {'L3', 'R3'}, 3: {'L1', 'R1', 'Start', 'Select'},
    4: {'Start', 'Select'}, 5: {'L3', 'R1'}, 6: {'L1', 'R1'}, 9: {'↓', 'Select'},
    10: {'L2', 'R2'},
}
# PhoneWindowManager.getFnShortcut: KeyEvent.KEYCODE_X -> label
FN_KEYS = {
    'VOLUME_UP': '@key_volume+', 'VOLUME_DOWN': '@key_volume-', 'BUTTON_START': 'Start',
    'BUTTON_SELECT': 'Select', 'BUTTON_Y': 'Y', 'BUTTON_X': 'X', 'BUTTON_R1': 'R1',
    'BUTTON_L2': 'L2', 'BUTTON_L1': 'L1', 'BUTTON_A': 'A', 'BUTTON_B': 'B',
    'BUTTON_R2': 'R2', 'BUTTON_THUMBL': 'L3', 'BUTTON_THUMBR': 'R3', 'DPAD_UP': '↑',
    'DPAD_DOWN': '↓', 'DPAD_LEFT': '←', 'DPAD_RIGHT': '→',
}


class Item:
    def __init__(self, kind, value, line, topic, heading):
        self.kind = kind
        self.value = value
        self.line = line
        self.topic = topic
        self.heading = heading
        self.conditions = []
        self.checks = []
        self.keys = []      # [[label, ...alternatives], ...] for rows
        self.text = None

    def all_conditions(self):
        out = list(self.conditions)
        for parent in (self.heading, self.topic):
            if parent is not None and parent is not self:
                out += parent.conditions
        return out

    def keys_text(self):
        return ' + '.join(' / '.join(group) for group in self.keys)


def parse(path):
    items = []
    topic = heading = last = None
    with open(path, encoding='utf-8') as f:
        for number, raw in enumerate(f, 1):
            line = raw.rstrip('\n')
            if not line.strip() or line.startswith('#'):
                continue
            if line.startswith(' '):
                if last is None:
                    raise SystemExit('%s:%d: condition before any item' % (path, number))
                word, _, rest = line.strip().partition(' ')
                if word == 'check':
                    last.checks.append((number, rest.strip()))
                elif word in ('if-package', 'if-file', 'if-prop'):
                    last.conditions.append((word, rest.strip()))
                else:
                    raise SystemExit('%s:%d: unknown line %r' % (path, number, line))
                continue
            kind, _, rest = line.partition(' ')
            if kind == 'drawing':
                continue
            item = Item(kind, rest, number, topic, heading)
            if kind == 'topic':
                topic, heading = item, None
                item.topic = None
            elif kind == 'heading':
                heading = item
                item.heading = None
            elif kind == 'row':
                keys, sep, text = rest.rpartition(' = ')
                if not sep:
                    raise SystemExit('%s:%d: row without " = "' % (path, number))
                item.keys = [[k.strip() for k in g.split(' / ')] for g in keys.split(' + ')]
                item.text = text.strip()
            elif kind == 'text':
                item.text = rest.strip()
            else:
                raise SystemExit('%s:%d: unknown item %r' % (path, number, kind))
            items.append(item)
            last = item
    return items


class Checker:
    def __init__(self, roots, verbose):
        self.roots = roots
        self.verbose = verbose
        self.errors = []
        self.notes = []
        self.skipped = {}
        self.cache = {}

    def error(self, item, message):
        self.errors.append('controls.txt:%d: %s' % (item.line, message) if item else message)

    def path(self, spec):
        root, _, rel = spec.partition(':')
        if root not in self.roots:
            raise KeyError('unknown root %r' % root)
        base = self.roots[root]
        return None if base is None else os.path.join(base, rel)

    def read(self, spec):
        """File text, or None when the root or the file is not there."""
        if spec in self.cache:
            return self.cache[spec]
        p = self.path(spec)
        text = None
        if p is not None and os.path.isfile(p):
            with open(p, encoding='utf-8', errors='replace') as f:
                text = f.read()
        self.cache[spec] = text
        return text

    def missing(self, item, spec):
        """A source file isn't in this tree: fine for rows hidden without it."""
        root = spec.partition(':')[0]
        if self.roots.get(root) is None:
            self.notes.append('skipped (no %s tree): %d %s' % (root, item.line, item.value))
            return
        if any(c[0] == 'if-file' for c in item.all_conditions()):
            self.skipped[spec] = self.skipped.get(spec, 0) + 1
            return
        self.error(item, '%s is missing, and the row has no if-file' % spec)

    # --- the key layout -------------------------------------------------------------------
    def kl(self):
        text = self.read('common:' + KL) or ''
        keys = {}
        for m in re.finditer(r'^key\s+(\d+)\s+(\w+)', text, re.M):
            keys.setdefault(m.group(2), set()).add(int(m.group(1)))
        return keys

    def kcm_fallback(self, key):
        text = self.read('common:' + KCM) or ''
        m = re.search(r'^key %s \{(.*?)\}' % re.escape(key), text, re.M | re.S)
        if not m:
            return None
        f = re.search(r'fallback\s+(\w+)', m.group(1))
        return f.group(1) if f else 'none'

    def code(self, label):
        return KEYCODES.get(LABELS.get(label) or '', None)

    def check_labels(self, item):
        kl = self.kl()
        for group in item.keys:
            for label in group:
                if label not in LABELS:
                    self.error(item, 'unknown button %r' % label)
                    continue
                key = LABELS[label]
                if key is not None and key not in kl:
                    self.error(item, '%s (%s) is not in %s' % (label, key, KL))

    # --- one check line ---------------------------------------------------------------------
    def run(self, item, number, spec):
        kind, _, arg = spec.partition(' ')
        arg = arg.strip()
        method = getattr(self, 'c_' + kind.replace('-', '_'), None)
        if method is None:
            self.error(item, 'unknown check %r' % kind)
            return
        method(item, arg)

    def c_kl(self, item, arg):
        kl = self.kl()
        words = arg.split()
        i = 0
        while i < len(words):
            key = words[i]
            scan = int(words[i + 1]) if i + 1 < len(words) and words[i + 1].isdigit() else None
            i += 2 if scan is not None else 1
            if key not in kl:
                self.error(item, '%s is not in %s' % (key, KL))
            elif scan is not None and scan not in kl[key]:
                self.error(item, '%s is not on scan code %d in %s' % (key, scan, KL))

    def c_kcm(self, item, arg):
        key, fallback = arg.split()
        got = self.kcm_fallback(key)
        if got != fallback:
            self.error(item, '%s falls back to %s in %s, not %s' % (key, got, KCM, fallback))

    def fn_shortcuts(self):
        text = self.read('fwb:' + PWM)
        if text is None:
            return None
        m = re.search(r'private static int getFnShortcut\(int keyCode\) \{(.*?)\n    \}', text,
                      re.S)
        if not m:
            return set()
        body = m.group(1)
        keys = set()
        pending = []
        for line in body.splitlines():
            c = re.search(r'case KeyEvent\.KEYCODE_(\w+):', line)
            if c:
                pending.append(c.group(1))
            elif re.search(r'return FN_HOTKEY_(?!NONE)\w+;', line):
                keys.update(pending)
                pending = []
            elif 'return' in line:
                pending = []
        return keys

    def c_fn(self, item, arg):
        shortcuts = self.fn_shortcuts()
        if shortcuts is None:
            self.missing(item, 'fwb:' + PWM)
            return
        if len(item.keys) != 2 or item.keys[0] != ['FN']:
            self.error(item, 'an FN row must be "FN + button"')
            return
        by_label = {v: k for k, v in FN_KEYS.items()}
        for label in item.keys[1]:
            key = by_label.get(label)
            if key not in shortcuts:
                self.error(item, 'FN + %s is not an FN shortcut in PhoneWindowManager' % label)

    def ra_cfg(self):
        text = self.read('common:' + RA_CFG)
        if text is None:
            return None
        return dict(re.findall(r'^(\w+)\s*=\s*"([^"]*)"', text, re.M))

    def hotkey_row(self, item, what):
        """A 'Select + K[ / K2]' row: returns the list of K labels, or None."""
        if len(item.keys) != 2 or item.keys[0] != ['Select']:
            self.error(item, 'a %s hotkey row must be "Select + button"' % what)
            return None
        return item.keys[1]

    def c_ra(self, item, arg):
        cfg = self.ra_cfg()
        if cfg is None:
            self.missing(item, 'common:' + RA_CFG)
            return
        labels = self.hotkey_row(item, 'RetroArch')
        if labels is None:
            return
        actions = arg.split()
        if len(actions) != len(labels):
            self.error(item, 'one RetroArch action per button needed')
            return
        if cfg.get('input_enable_hotkey_btn') != str(self.code('Select')):
            self.error(item, 'RetroArch hotkey button is %s, not Select'
                       % cfg.get('input_enable_hotkey_btn'))
        for label, action in zip(labels, actions):
            got = cfg.get('input_%s_btn' % action)
            if got != str(self.code(label)):
                self.error(item, 'RetroArch input_%s_btn is %s, the page says %s (%s)'
                           % (action, got, label, self.code(label)))
            self.ra_seen.add(action)

    def c_ra_combo(self, item, arg):
        cfg = self.ra_cfg()
        if cfg is None:
            self.missing(item, 'common:' + RA_CFG)
            return
        value = cfg.get('input_menu_toggle_gamepad_combo')
        want = RA_COMBOS.get(int(value)) if value and value.isdigit() else None
        got = {g[0] for g in item.keys}
        if want != got:
            self.error(item, 'RetroArch menu combo %s is %s, the page says %s'
                       % (value, sorted(want or []), sorted(got)))
        self.ra_seen.add('menu_toggle_gamepad_combo')

    def ppsspp(self):
        text = self.read('common:' + PPSSPP)
        if text is None:
            return None
        return {k.strip(): [b.strip() for b in v.split(',')]
                for k, v in re.findall(r'^([^=\n]+)=(.*)$', text, re.M)}

    def c_ppsspp(self, item, arg):
        ini = self.ppsspp()
        if ini is None:
            self.missing(item, 'common:' + PPSSPP)
            return
        names = arg.split('|')
        if len(item.keys) == 2 and item.keys[0] == ['Select']:
            binds = ['10-%d:10-%d' % (self.code('Select'), self.code(k)) for k in item.keys[1]]
        elif len(item.keys) == 1:
            binds = ['10-%d' % self.code(k) for k in item.keys[0]]
        else:
            self.error(item, 'a PPSSPP row is "Select + button" or one button')
            return
        if len(names) == 1:
            names = names * len(binds)
        if len(names) != len(binds):
            self.error(item, 'one PPSSPP name per button needed')
            return
        for name, bind in zip(names, binds):
            if bind not in ini.get(name, []):
                self.error(item, 'PPSSPP %r does not have %s (it has %s)'
                           % (name, bind, ini.get(name)))
            self.ppsspp_seen.add((name, bind))

    def flycast(self):
        text = self.read('common:' + FLYCAST)
        if text is None:
            return None
        combo = text.partition('[combo]')[2]
        return set(re.findall(r'^bind\d+\s*=\s*([\d,]+):(\w+):\d+', combo, re.M))

    def c_flycast(self, item, arg):
        binds = self.flycast()
        if binds is None:
            self.missing(item, 'common:' + FLYCAST)
            return
        labels = self.hotkey_row(item, 'Flycast')
        if labels is None:
            return
        actions = arg.split('|')
        if len(actions) != len(labels):
            self.error(item, 'one Flycast action per button needed')
            return
        for label, action in zip(labels, actions):
            want = ('%d,%d' % (self.code('Select'), self.code(label)), action)
            if want not in binds:
                self.error(item, 'Flycast has no combo %s:%s' % want)
            self.flycast_seen.add(want)

    def mupen(self):
        text = self.read('common:' + MUPEN)
        if text is None:
            return None
        m = re.search(r'^map=(.*)$', text, re.M)
        pairs = set()
        for part in (m.group(1) if m else '').split(','):
            if ':' in part:
                a, b = part.split(':')
                pairs.add((int(a), int(b)))
        return pairs

    def c_mupen(self, item, arg):
        pairs = self.mupen()
        if pairs is None:
            self.missing(item, 'common:' + MUPEN)
            return
        if len(item.keys) != 1:
            self.error(item, 'a Mupen64Plus row is one button (the app has no combos)')
            return
        for label in item.keys[0]:
            if (int(arg), self.code(label)) not in pairs:
                self.error(item, 'Mupen64Plus command %s is not on %s' % (arg, label))
            self.mupen_seen.add(int(arg))

    def mouse_defaults(self):
        text = self.read('common:' + MOUSE) or ''
        m = re.search(r'Bindings defaultBindings\(\) \{(.*?)\n\}', text, re.S)
        return dict(re.findall(r'b\[index\(Button::(\w+)\)\] = Action::(\w+);',
                               m.group(1) if m else ''))

    def c_mouse(self, item, arg):
        defaults = self.mouse_defaults()
        if len(item.keys) != 1:
            self.error(item, 'a mouse mode row is one button or alternatives')
            return
        actions = arg.split()
        labels = item.keys[0]
        if len(actions) != len(labels):
            self.error(item, 'one mouse action per button needed')
            return
        for label, action in zip(labels, actions):
            got = defaults.get(MOUSE_BUTTONS.get(label, label), 'Pass')
            if got != action:
                self.error(item, 'joyMouse gives %s the action %s, the page says %s'
                           % (label, got, action))
            self.mouse_seen.add(MOUSE_BUTTONS.get(label, label))

    def c_has(self, item, arg, want=True):
        spec, _, regex = arg.partition(' ')
        text = self.read(spec)
        if text is None:
            self.missing(item, spec)
            return
        if bool(re.search(regex, text, re.M)) != want:
            self.error(item, '%s %s /%s/' % (spec, 'lacks' if want else 'has', regex))

    def c_lacks(self, item, arg):
        self.c_has(item, arg, want=False)

    def c_exists(self, item, arg):
        p = self.path(arg)
        if p is None:
            self.notes.append('skipped (no %s tree): %s' % (arg.partition(':')[0], arg))
        elif not os.path.exists(p):
            self.error(item, '%s does not exist' % arg)

    def c_hardware(self, item, arg):
        self.notes.append('not compared (hardware): %s: %s' % (item.keys_text(), arg))

    # --- the whole file ---------------------------------------------------------------------
    def check(self, items):
        self.ra_seen = set()
        self.ppsspp_seen = set()
        self.flycast_seen = set()
        self.mupen_seen = set()
        self.mouse_seen = set()
        fn_rows = set()
        for item in items:
            if item.kind == 'row':
                self.check_labels(item)
                if not item.checks:
                    self.error(item, 'row "%s" has no check' % item.keys_text())
                if any(c[1].split()[0] == 'fn' for c in item.checks):
                    for label in item.keys[1] if len(item.keys) == 2 else []:
                        fn_rows.add(label)
            for number, spec in item.checks:
                self.run(item, number, spec)
            if self.verbose and item.kind == 'row':
                print('  row %-32s %s' % (item.keys_text(), item.text))
        self.complete(fn_rows)

    def complete(self, fn_rows):
        """Every combination the build has is on the page."""
        shortcuts = self.fn_shortcuts()
        if shortcuts is not None:
            for key in sorted(shortcuts):
                if FN_KEYS.get(key) not in fn_rows:
                    self.error(None, 'FN + %s is a shortcut in PhoneWindowManager but no row '
                               'of controls.txt shows it' % key)
        cfg = self.ra_cfg()
        if cfg is not None:
            for name in sorted(cfg):
                m = re.match(r'input_(\w+)_btn$', name)
                if m and m.group(1) != 'enable_hotkey' and m.group(1) not in self.ra_seen:
                    self.error(None, 'RetroArch hotkey %s is seeded but not on the page' % name)
            if cfg.get('input_menu_toggle_gamepad_combo', '0') != '0' and \
                    'menu_toggle_gamepad_combo' not in self.ra_seen:
                self.error(None, 'RetroArch menu combo is seeded but not on the page')
        ini = self.ppsspp()
        if ini is not None:
            for name, binds in sorted(ini.items()):
                for bind in binds:
                    if ':' in bind and (name, bind) not in self.ppsspp_seen:
                        self.error(None, 'PPSSPP %s = %s is seeded but not on the page'
                                   % (name, bind))
        binds = self.flycast()
        if binds is not None:
            for bind in sorted(binds - self.flycast_seen):
                self.error(None, 'Flycast combo %s:%s is seeded but not on the page' % bind)
        pairs = self.mupen()
        if pairs is not None:
            # 26 and up are the app's own actions (fast forward, menu, screenshot...)
            for command in sorted({c for c, _ in pairs if c >= 26} - self.mupen_seen):
                self.error(None, 'Mupen64Plus command %d is seeded but not on the page'
                           % command)
        for button, action in sorted(self.mouse_defaults().items()):
            if action not in ('Pass', 'None') and button not in self.mouse_seen:
                self.error(None, 'joyMouse gives %s the action %s but the page doesn\'t say'
                           % (button, action))

    # --- strings ----------------------------------------------------------------------------
    def strings(self, items, app):
        res = os.path.join(app, 'res')
        english = {}
        own = set()
        for name in sorted(os.listdir(os.path.join(res, 'values'))):
            if not name.endswith('.xml'):
                continue
            names = self.xml_strings(os.path.join(res, 'values', name),
                                     name == 'controls_strings.xml')
            english.update(dict.fromkeys(names))
            if name == 'controls_strings.xml':
                own = set(names)
        used = set()
        for item in items:
            for value in (item.value if item.kind in ('topic', 'heading') else None, item.text):
                if value and not value.startswith('='):
                    used.add(value)
            for group in item.keys:
                for label in group:
                    m = re.match(r'@(\w+)', label)
                    if m:
                        used.add(m.group(1))
        for name in sorted(used - set(english)):
            self.error(None, 'string %s is used in controls.txt but not in res/values' % name)
        for name in sorted(own - used):
            self.error(None, 'string %s in controls_strings.xml is not used' % name)
        languages = 0
        for folder in sorted(os.listdir(res)):
            path = os.path.join(res, folder, 'controls_strings.xml')
            if folder == 'values' or not folder.startswith('values-') or \
                    not os.path.isfile(path):
                continue
            languages += 1
            names = set(self.xml_strings(path, True))
            for name in sorted(own - names):
                self.error(None, '%s/controls_strings.xml lacks %s' % (folder, name))
            for name in sorted(names - own):
                self.error(None, '%s/controls_strings.xml has %s, English doesn\'t'
                           % (folder, name))
        return len(used), languages

    def xml_strings(self, path, ours=False):
        try:
            dom = xml.dom.minidom.parse(path)
        except Exception as e:  # noqa: BLE001 - any parse error is a finding
            self.error(None, '%s: %s' % (path, e))
            return []
        names = []
        for node in dom.getElementsByTagName('string'):
            names.append(node.getAttribute('name'))
            text = ''.join(n.data for n in node.childNodes if n.nodeType == n.TEXT_NODE)
            if ours and (re.search(r"(?<!\\)'", text) or re.search(r'(?<!\\)"', text)):
                self.error(None, '%s: %s has an unescaped quote' % (path, node.getAttribute('name')))
        return names


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--app', default=APP, help='the QuickStart folder (data + strings)')
    parser.add_argument('--common', default=os.path.dirname(APP))
    parser.add_argument('--fwb', default=os.environ.get('FWB', '/andr36oid/src/frameworks/base'))
    parser.add_argument('--r36s', default=os.environ.get(
        'R36S', '/andr36oid/src/device/gameconsole/r36s'))
    parser.add_argument('-v', '--verbose', action='store_true')
    args = parser.parse_args()

    roots = {}
    for name in ('common', 'fwb', 'r36s'):
        path = getattr(args, name)
        roots[name] = path if path and os.path.isdir(path) else None
    checker = Checker(roots, args.verbose)
    items = parse(os.path.join(args.app, 'res', 'raw', 'controls.txt'))
    checker.check(items)
    used, languages = checker.strings(items, args.app)

    rows = sum(1 for i in items if i.kind == 'row')
    for spec, count in sorted(checker.skipped.items()):
        checker.notes.append('%d rows skipped: %s is not in this tree (they are hidden '
                             'without it, if-file)' % (count, spec))
    for note in checker.notes:
        print('note: ' + note)
    for error in checker.errors:
        print('ERROR: ' + error)
    print('%d rows, %d strings, %d translations; %d errors'
          % (rows, used, languages, len(checker.errors)))
    return 1 if checker.errors else 0


if __name__ == '__main__':
    sys.exit(main())
