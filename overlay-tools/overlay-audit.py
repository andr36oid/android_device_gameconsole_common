#!/usr/bin/env python3
# Overlay audit: does every value in our resource overlays land in the built
# APKs, and does the console pick it at run time?
#
# Our overlays are static overlays (no enforced RRO). The build links the
# app's res, then every overlay layer with aapt2 -R, and the last one wins:
#   device overlays (DEVICE_PACKAGE_OVERLAYS, last listed first) then
#   product overlays (PRODUCT_PACKAGE_OVERLAYS, last listed first).
# So the first listed PRODUCT overlay beats everything. Lineage's
# vendor/lineage/overlay/common is a product overlay, so any key it shares
# with our device overlays is Lineage's (see build/soong/java/android_resources.go
# and build/make/core/package_internal.mk).
#
# Even when our entry is in the APK, the console can pick another folder:
# a more specific qualifier in the base app (values-land, values-night,
# values-sw600dp...). This script resolves each key for the device profile
# (640x480 at density 178, landscape, dark theme by default, our languages)
# and lists every key where the console does not get our value.
#
# Usage:
#   overlay-audit.py --src /andr36oid/src                 # check, exit 1 on a loser
#   overlay-audit.py --src /andr36oid/src --common DIR    # use another common checkout
#   overlay-audit.py --src /andr36oid/src --all           # also list keys that are fine
#   overlay-audit.py --src /andr36oid/src --profile square178
#
# Read-only: runs aapt2 dump on the built APKs, never writes to the tree.

import argparse
import json
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

# ---------------------------------------------------------------- profiles
# dp = px * 160 / density. Landscape 640x480 at 178: 575 x 431 dp; the status
# bar (24 dp) comes off the height; smallest width is the short side.
# size: normal (long side < 640 dp). notlong: 575/431 is not "long".
PROFILES = {
    'r36s': dict(desc='640x480 at density 178 (R36S and clones)',
                 orient='land', sw=431, w=575, h=407, size='normal', long=False,
                 density=178),
    'square178': dict(desc='720x720 at density 178 (square clones today)',
                      orient='port', sw=647, w=647, h=623, size='large', long=False,
                      density=178),
    'square200': dict(desc='720x720 at density 200 (square clones with O2-88)',
                      orient='port', sw=576, w=576, h=552, size='normal', long=False,
                      density=200),
}
# Fixed for all our consoles: no SIM (mcc 0), LTR scripts for our languages,
# no touch, no alphabetic keyboard, a d-pad, API 30, normal UI mode.
LOCALES = ['en-US', 'en-GB', 'es-ES', 'es-US', 'pt-BR', 'pt-PT', 'fr-FR', 'fr-CA',
           'de-DE', 'ru-RU', 'pl-PL', 'tr-TR', 'in-ID', 'zh-CN', 'zh-TW']
SDK = 30
SIZES = ['small', 'normal', 'large', 'xlarge']
DENS = {'ldpi': 120, 'mdpi': 160, 'tvdpi': 213, 'hdpi': 240, 'xhdpi': 320,
        'xxhdpi': 480, 'xxxhdpi': 640, 'nodpi': 0xffff, 'anydpi': 0xfffe}
UIMODES = {'car', 'desk', 'television', 'appliance', 'watch', 'vrheadset'}

# Module res dir (relative to the tree) -> built APK candidates (relative to out/).
APKS = {
    'frameworks/base/core/res/res': [
        'soong/.intermediates/frameworks/base/core/res/framework-res/android_common/framework-res.apk'],
    'frameworks/base/packages/SystemUI/res': [
        'soong/.intermediates/frameworks/base/packages/SystemUI/SystemUI/android_common/SystemUI.apk'],
    'frameworks/base/packages/SettingsProvider/res': [
        'soong/.intermediates/frameworks/base/packages/SettingsProvider/SettingsProvider/android_common/SettingsProvider.apk'],
    'frameworks/base/packages/Tethering/res': [
        'soong/.intermediates/frameworks/base/packages/Tethering/InProcessTethering/android_common_apex10000/InProcessTethering.apk',
        'soong/.intermediates/frameworks/base/packages/Tethering/Tethering/android_common_apex10000/Tethering.apk'],
    'packages/modules/NetworkStack/res': [
        'soong/.intermediates/packages/modules/NetworkStack/InProcessNetworkStack/android_common/InProcessNetworkStack.apk',
        'soong/.intermediates/packages/modules/NetworkStack/NetworkStack/android_common/NetworkStack.apk'],
    'packages/apps/Settings/res': [
        'soong/.intermediates/packages/apps/Settings/Settings/android_common/Settings.apk'],
    'packages/apps/Bluetooth/res': [
        'soong/.intermediates/packages/apps/Bluetooth/Bluetooth/android_common/Bluetooth.apk'],
    'packages/inputmethods/LeanbackIME/res': [
        'soong/.intermediates/packages/inputmethods/LeanbackIME/LeanbackIME/android_common/LeanbackIME.apk'],
    'lineage-sdk/lineage/res/res': [
        'target/common/obj/APPS/org.lineageos.platform-res_intermediates/package-export.apk'],
    'lineage-sdk/packages/LineageSettingsProvider/res': [
        'target/common/obj/APPS/LineageSettingsProvider_intermediates/package-export.apk'],
}

OURS_PREFIX = 'device/gameconsole/'


# ---------------------------------------------------------------- qualifiers
def parse_quals(s):
    """'sw600dp-land-v13' -> dict. Returns None for folders we can't read."""
    q = {}
    toks = [t for t in s.split('-') if t] if s else []
    i = 0
    while i < len(toks):
        t = toks[i]
        tl = t.lower()
        if re.fullmatch(r'mcc\d+', tl):
            q['mcc'] = t
        elif re.fullmatch(r'mnc\d+', tl):
            q['mnc'] = t
        elif tl.startswith('b+'):
            parts = t[2:].split('+')
            q['lang'] = parts[0].lower()
            for p in parts[1:]:
                if len(p) == 4:
                    q['script'] = p
                elif len(p) in (2, 3):
                    q['region'] = p.upper()
        elif re.fullmatch(r'[a-z]{2,3}', t) and 'lang' not in q and t not in (
                'car', 'dpad', 'land', 'port', 'long'):
            q['lang'] = t
            if i + 1 < len(toks) and re.fullmatch(r'r[A-Z]{2}|r\d{3}', toks[i + 1]):
                q['region'] = toks[i + 1][1:]
                i += 1
        elif tl in ('ldrtl', 'ldltr'):
            q['layoutdir'] = tl
        elif re.fullmatch(r'sw\d+dp', tl):
            q['sw'] = int(tl[2:-2])
        elif re.fullmatch(r'w\d+dp', tl):
            q['w'] = int(tl[1:-2])
        elif re.fullmatch(r'h\d+dp', tl):
            q['h'] = int(tl[1:-2])
        elif tl in SIZES:
            q['size'] = tl
        elif tl in ('long', 'notlong'):
            q['long'] = tl
        elif tl in ('round', 'notround'):
            q['round'] = tl
        elif tl in ('widecg', 'nowidecg'):
            q['widecg'] = tl
        elif tl in ('highdr', 'lowdr'):
            q['hdr'] = tl
        elif tl in ('port', 'land', 'square'):
            q['orient'] = tl
        elif tl in UIMODES:
            q['uimode'] = tl
        elif tl in ('night', 'notnight'):
            q['night'] = tl
        elif tl in DENS:
            q['density'] = DENS[tl]
        elif re.fullmatch(r'\d+dpi', tl):
            q['density'] = int(tl[:-3])
        elif tl in ('notouch', 'finger', 'stylus'):
            q['touch'] = tl
        elif tl in ('keysexposed', 'keyshidden', 'keyssoft'):
            q['keyshidden'] = tl
        elif tl in ('nokeys', 'qwerty', '12key'):
            q['keyboard'] = tl
        elif tl in ('navexposed', 'navhidden'):
            q['navhidden'] = tl
        elif tl in ('nonav', 'dpad', 'trackball', 'wheel'):
            q['nav'] = tl
        elif re.fullmatch(r'v\d+', tl):
            q['v'] = int(tl[1:])
        elif re.fullmatch(r'\d+x\d+', tl):
            q['screen'] = tl
        else:
            return None
        i += 1
    return q


def cfg_key(q):
    """Config identity without the -vNN the build adds."""
    return tuple(sorted((k, v) for k, v in q.items() if k != 'v'))


def cfg_str(q):
    if not q:
        return '()'
    order = ['mcc', 'mnc', 'lang', 'script', 'region', 'layoutdir', 'sw', 'w', 'h', 'size',
             'long', 'round', 'widecg', 'hdr', 'orient', 'uimode', 'night', 'density',
             'touch', 'keyshidden', 'keyboard', 'navhidden', 'nav', 'v']
    inv = {v: k for k, v in DENS.items()}
    out = []
    for k in order:
        if k not in q:
            continue
        v = q[k]
        if k == 'region':
            out.append('r' + v)
        elif k in ('sw',):
            out.append('sw%ddp' % v)
        elif k in ('w', 'h'):
            out.append('%s%ddp' % (k, v))
        elif k == 'density':
            out.append(inv.get(v, '%ddpi' % v))
        elif k == 'v':
            out.append('v%d' % v)
        else:
            out.append(str(v))
    return '(' + '-'.join(out) + ')'


def matches(q, dev):
    if 'mcc' in q or 'mnc' in q or 'screen' in q:
        return False
    if 'lang' in q:
        if q['lang'] != dev['lang']:
            return False
        if 'region' in q and q['region'] != dev['region']:
            return False
        if 'script' in q and q['script'] != dev.get('script', q['script']):
            return False
    if q.get('layoutdir') == 'ldrtl':
        return False
    for k in ('sw', 'w', 'h'):
        if k in q and q[k] > dev[k]:
            return False
    if 'size' in q and SIZES.index(q['size']) > SIZES.index(dev['size']):
        return False
    if 'long' in q and (q['long'] == 'long') != dev['long']:
        return False
    if q.get('round') == 'round' or q.get('widecg') == 'widecg' or q.get('hdr') == 'highdr':
        return False
    if 'orient' in q and q['orient'] != dev['orient']:
        return False
    if 'uimode' in q:
        return False
    if 'night' in q and q['night'] != dev['night']:
        return False
    if q.get('touch') in ('finger', 'stylus'):
        return False
    # No hardware keyboard, soft keyboard available: keyssoft; keysexposed also matches.
    if q.get('keyshidden') == 'keyshidden':
        return False
    if q.get('keyboard') in ('qwerty', '12key'):
        return False
    if q.get('navhidden') == 'navhidden':
        return False
    if q.get('nav') in ('nonav', 'trackball', 'wheel'):
        return False
    if q.get('v', 0) > SDK:
        return False
    return True


def density_better(a, b, want):
    """True if density a is a better pick than b for the wanted density."""
    if a == b:
        return False
    a = 160 if a is None else a
    b = 160 if b is None else b
    if a == 0xfffe:
        return True
    if b == 0xfffe:
        return False
    if a >= want and b >= want:
        return a < b
    if a >= want:
        return True
    if b >= want:
        return False
    return a > b


def resolve(cands, dev):
    """cands: list of qualifier dicts. Returns the index Android would pick."""
    live = [i for i, q in enumerate(cands) if matches(q, dev)]
    if not live:
        return None

    def keep(pred):
        nonlocal live
        sel = [i for i in live if pred(cands[i])]
        if sel:
            live = sel

    def keep_max(k, conv=lambda v: v):
        nonlocal live
        if any(k in cands[i] for i in live):
            best = max(conv(cands[i][k]) for i in live if k in cands[i])
            live = [i for i in live if k in cands[i] and conv(cands[i][k]) == best]

    # Android precedence order (developer guide, table 2).
    keep(lambda q: 'lang' in q and 'region' in q)
    keep(lambda q: 'lang' in q)
    keep(lambda q: 'layoutdir' in q)
    keep_max('sw')
    keep_max('w')
    keep_max('h')
    keep_max('size', SIZES.index)
    for k in ('long', 'round', 'widecg', 'hdr', 'orient', 'uimode', 'night'):
        keep(lambda q, k=k: k in q)
    if len(live) > 1:
        best = live[0]
        for i in live[1:]:
            if density_better(cands[i].get('density'), cands[best].get('density'), dev['density']):
                best = i
        bd = cands[best].get('density')
        live = [i for i in live if cands[i].get('density') == bd]
    for k in ('touch', 'keyshidden', 'keyboard', 'navhidden', 'nav'):
        keep(lambda q, k=k: k in q)
    keep_max('v')
    return live[0]


# ---------------------------------------------------------------- values
VALUE_TAGS = {'bool': 'bool', 'integer': 'integer', 'string': 'string', 'dimen': 'dimen',
              'color': 'color', 'fraction': 'fraction', 'plurals': 'plurals',
              'string-array': 'array', 'integer-array': 'array', 'array': 'array',
              'style': 'style', 'drawable': 'drawable', 'attr': 'attr', 'id': 'id'}


def unescape(s):
    s = s.strip()
    s = re.sub(r'\s+', ' ', s)
    if len(s) >= 2 and s[0] == '"' and s[-1] == '"':
        s = s[1:-1]
    out = []
    i = 0
    while i < len(s):
        c = s[i]
        if c == '\\' and i + 1 < len(s):
            n = s[i + 1]
            out.append({'n': '\n', 't': '\t'}.get(n, n))
            i += 2
            continue
        if c == '"':
            i += 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def norm_scalar(t, s, from_apk=False):
    s = s.strip()
    if t == 'bool':
        return s.lower()
    if t == 'integer':
        try:
            return str(int(s, 0))
        except ValueError:
            return s
    if t in ('dimen', 'fraction'):
        m = re.fullmatch(r'(-?[\d.]+)\s*([a-z%]*)', s)
        if m:
            num, unit = float(m.group(1)), m.group(2)
            if unit == 'dip':
                unit = 'dp'
            if t == 'fraction' and not from_apk:
                num /= 100.0    # aapt2 dump prints 50% as 0.500000%
            return '%g%s' % (num, unit)
        return s
    if t == 'color':
        return s.lower()
    if s.startswith('@'):
        return s.replace('@*android:', '@android:')
    return unescape(s)


def xml_text(el):
    return (el.text or '') + ''.join(ET.tostring(c, encoding='unicode') for c in el)


def src_value(t, el):
    if t == 'array':
        tag = el.tag
        it = 'integer' if tag == 'integer-array' else 'string'
        return '[' + ', '.join(norm_scalar(it, xml_text(i)) for i in el.findall('item')) + ']'
    if t in ('plurals', 'style'):
        return '(%s)' % t
    return norm_scalar(t, xml_text(el))


def apk_value(t, raw):
    raw = raw.strip()
    if raw.startswith('(file)'):
        return raw
    if raw.startswith('(array)'):
        body = raw.split(None, 2)[2] if len(raw.split(None, 2)) > 2 else ''
        body = body.strip()
        if body.startswith('[') and body.endswith(']'):
            body = body[1:-1]
        items = re.findall(r'"((?:[^"\\]|\\.)*)"|([^,\s][^,]*)', body)
        vals = []
        for a, b in items:
            v = a if a or not b else b.strip()
            vals.append(v.replace('\\"', '"') if a else norm_scalar('integer', v, True))
        return '[' + ', '.join(vals) + ']'
    if raw.startswith('('):
        return raw
    if raw.startswith('"') and raw.endswith('"'):
        return raw[1:-1].replace('\\"', '"').replace('\\n', '\n')
    return norm_scalar(t, raw, True)


# ---------------------------------------------------------------- layers
def layer_order(src, common, r36s):
    """Priority list, highest first: product overlays in listed order, then
    device overlays in listed order. Our part comes from device.mk (so an
    unbuilt change to the order is seen); Lineage's from soong.variables."""
    def mk_list(path, var):
        out = []
        if not os.path.exists(path):
            return out, []
        txt = open(path, encoding='utf-8').read()
        # join continuation lines
        txt = re.sub(r'\\\n', ' ', txt)
        lineage_at = None
        for n, line in enumerate(txt.splitlines()):
            s = line.split('#', 1)[0]
            if 'inherit-product' in s and 'vendor/lineage/config/' in s and lineage_at is None:
                lineage_at = len(out)
            m = re.match(r'\s*%s\s*\+?:?=\s*(.*)' % var, s)
            if m:
                for d in m.group(1).split():
                    d = d.replace('$(LOCAL_PATH)', os.path.relpath(os.path.dirname(path), src)
                                  if path.startswith(src) else 'device/gameconsole/'
                                  + os.path.basename(os.path.dirname(path)))
                    out.append(d)
        return out, lineage_at

    cm = os.path.join(common, 'device.mk')
    rm = os.path.join(r36s, 'device.mk')
    our_prod, lin_at = mk_list(cm, 'PRODUCT_PACKAGE_OVERLAYS')
    our_prod2, _ = mk_list(rm, 'PRODUCT_PACKAGE_OVERLAYS')
    our_dev, _ = mk_list(cm, 'DEVICE_PACKAGE_OVERLAYS')
    our_dev2, _ = mk_list(rm, 'DEVICE_PACKAGE_OVERLAYS')

    sv = os.path.join(src, 'out/soong/soong.variables')
    built_prod, built_dev = None, None
    lineage_prod = ['vendor/lineage/overlay/common', 'vendor/crowdin/overlay']
    if os.path.exists(sv):
        d = json.load(open(sv))
        built_prod = d.get('ProductResourceOverlays') or []
        built_dev = d.get('DeviceResourceOverlays') or []
        lineage_prod = [p for p in built_prod if not p.startswith(OURS_PREFIX)]
    if lin_at is None:
        lin_at = len(our_prod)
    prod = our_prod[:lin_at] + lineage_prod + our_prod[lin_at:] + our_prod2
    dev = our_dev + our_dev2
    layers = [(p, 'product') for p in prod] + [(p, 'device') for p in dev]
    built = None
    if built_prod is not None:
        built = [(p, 'product') for p in built_prod] + [(p, 'device') for p in built_dev]
    return layers, built


def layer_path(src, common, r36s, layer):
    if layer.startswith('device/gameconsole/common/'):
        return os.path.join(common, layer[len('device/gameconsole/common/'):])
    if layer.startswith('device/gameconsole/r36s/'):
        return os.path.join(r36s, layer[len('device/gameconsole/r36s/'):])
    return os.path.join(src, layer)


def scan_resdir(resdir):
    """Yield (type, name, quals, value, file, product) for one res dir."""
    if not os.path.isdir(resdir):
        return
    for folder in sorted(os.listdir(resdir)):
        fpath = os.path.join(resdir, folder)
        if not os.path.isdir(fpath):
            continue
        base, _, qs = folder.partition('-')
        q = parse_quals(qs)
        if q is None:
            continue
        for fn in sorted(os.listdir(fpath)):
            p = os.path.join(fpath, fn)
            if base == 'values':
                if not fn.endswith('.xml'):
                    continue
                try:
                    root = ET.parse(p).getroot()
                except ET.ParseError as e:
                    print('WARN: cannot parse %s: %s' % (p, e), file=sys.stderr)
                    continue
                for el in root:
                    if not isinstance(el.tag, str):
                        continue
                    name = el.get('name')
                    if not name:
                        continue
                    t = el.get('type') if el.tag == 'item' else VALUE_TAGS.get(el.tag)
                    if t is None or t in ('attr', 'id'):
                        continue
                    yield t, name, q, src_value(t, el), p, el.get('product')
            else:
                name = fn.split('.')[0]
                yield base, name, q, '(file %s)' % fn, p, None


# ---------------------------------------------------------------- aapt2
_dumps = {}


def aapt2_dump(aapt2, apk):
    if apk in _dumps:
        return _dumps[apk]
    res = {}
    try:
        out = subprocess.run([aapt2, 'dump', 'resources', apk], stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, check=True).stdout.decode('utf-8', 'replace')
    except (subprocess.CalledProcessError, OSError) as e:
        print('WARN: aapt2 dump failed for %s: %s' % (apk, e), file=sys.stderr)
        _dumps[apk] = None
        return None
    cur = None
    last = None
    for line in out.splitlines():
        m = re.match(r'\s+resource 0x[0-9a-f]+ (\S+)', line)
        if m:
            cur = res.setdefault(m.group(1), [])
            last = None
            continue
        if cur is None:
            continue
        m = re.match(r'      \(([^)]*)\) (.*)', line)
        if m:
            last = [m.group(1), m.group(2)]
            cur.append(last)
            continue
        if last is not None and line.startswith('        '):
            last[1] += ' ' + line.strip()
        elif line.startswith('  type ') or line.startswith('Package'):
            cur = None
    _dumps[apk] = res
    return res


# ---------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--src', default='/andr36oid/src', help='Android tree (read-only)')
    ap.add_argument('--common', help='device/gameconsole/common checkout (default: in --src)')
    ap.add_argument('--r36s', help='device/gameconsole/r36s checkout (default: in --src)')
    ap.add_argument('--out', help='out dir (default: SRC/out)')
    ap.add_argument('--aapt2', help='aapt2 binary')
    ap.add_argument('--profile', default='r36s', choices=sorted(PROFILES))
    ap.add_argument('--all', action='store_true', help='also print keys that are fine')
    ap.add_argument('--no-apk', action='store_true', help='source layers only, no aapt2')
    ap.add_argument('--skip-locale', action='store_true',
                    help="don't count strings hidden by language folders (l10n-regions.py checks those)")
    ap.add_argument('--tsv', help='write every checked key to this TSV file')
    a = ap.parse_args()

    src = os.path.abspath(a.src)
    common = os.path.abspath(a.common or os.path.join(src, 'device/gameconsole/common'))
    r36s = os.path.abspath(a.r36s or os.path.join(src, 'device/gameconsole/r36s'))
    out = os.path.abspath(a.out or os.path.join(src, 'out'))
    aapt2 = a.aapt2 or os.path.join(src, 'prebuilts/sdk/tools/linux/bin/aapt2')
    prof = PROFILES[a.profile]

    layers, built = layer_order(src, common, r36s)
    print('Profile %s: %s' % (a.profile, prof['desc']))
    print('Overlay layers, highest priority first:')
    for n, (l, kind) in enumerate(layers):
        print('  %d. %-45s %s%s' % (n + 1, l, kind, '  (ours)' if l.startswith(OURS_PREFIX) else ''))
    if built is not None and [l for l, _ in built] != [l for l, _ in layers]:
        print('NOTE: the last build used another order (out/soong/soong.variables):')
        for n, (l, kind) in enumerate(built):
            print('  %d. %-45s %s' % (n + 1, l, kind))
        print('  The APK values below are from that build.')

    aapt_sizes = None
    sv = os.path.join(out, 'soong/soong.variables')
    if os.path.exists(sv):
        cfg = json.load(open(sv)).get('AAPTConfig') or []
        aapt_sizes = {t for c in cfg for t in c.split(',') if t in SIZES} or None
    product_char = 'tablet'

    # Our res dirs first; other layers are only read for those.
    RES_RE = r'(values|drawable|layout|xml|mipmap|raw|color|anim|menu|font|interpolator)(-|$)'

    def find_resdirs(root):
        found = []
        for dirpath, dirnames, _files in os.walk(root):
            dirnames.sort()
            if any(re.match(RES_RE, d) for d in dirnames):
                found.append(os.path.relpath(dirpath, root))
                dirnames[:] = []
        return found

    our_resdirs = set()
    for layer, _ in layers:
        if layer.startswith(OURS_PREFIX):
            root = layer_path(src, common, r36s, layer)
            if os.path.isdir(root):
                our_resdirs.update(find_resdirs(root))

    # (resdir, type, name) -> {cfgkey: [(layerindex, value, quals)], highest layer first}
    our_entries = []   # (resdir, type, name, quals, value, file, layerindex)
    index = {}
    for li, (layer, _) in enumerate(layers):
        root = layer_path(src, common, r36s, layer)
        for resdir in sorted(our_resdirs):
            d = os.path.join(root, resdir)
            if not os.path.isdir(d):
                continue
            for t, name, q, val, f, prod in scan_resdir(d):
                if prod and prod not in (product_char, 'default'):
                    continue
                slot = index.setdefault((resdir, t, name), {}).setdefault(cfg_key(q), [])
                # within one layer, product="tablet" beats product="default"
                if prod == product_char:
                    slot[:] = [e for e in slot if e[0] != li]
                elif any(e[0] == li for e in slot):
                    continue
                slot.append((li, val, q))
                if layer.startswith(OURS_PREFIX):
                    our_entries.append((resdir, t, name, q, val, f, li))

    # de-duplicate our entries (tablet/default products)
    seen = set()
    ours = []
    for e in our_entries:
        key = (e[0], e[1], e[2], cfg_key(e[3]), e[6])
        if key in seen:
            continue
        seen.add(key)
        ours.append(e)

    scenarios = []
    for loc in LOCALES:
        lang, _, region = loc.partition('-')
        for night in ('night', 'notnight'):
            d = dict(prof)
            d.update(lang=lang, region=region, night=night)
            scenarios.append(('%s,%s' % (loc, night), d))

    losers = 0
    rows = []
    for resdir, t, name, q, val, f, li in sorted(ours, key=lambda e: (e[0], e[1], e[2], cfg_str(e[3]))):
        layer = layers[li][0]
        slots = index.get((resdir, t, name), {})
        problems = []
        status = 'OK'
        apk_note = ''
        # 1. same folder, higher layer
        top = slots.get(cfg_key(q), [])
        winner_li, winner_val = top[0][0], top[0][1]
        if winner_li != li:
            wl = layers[winner_li][0]
            same = winner_val == val
            problems.append('%s %s: %s wins with %s' % (
                'same value' if same else 'LOSES', cfg_str(q), wl, winner_val))
            if not same:
                status = 'LOSES-LAYER'
            elif status == 'OK':
                status = 'SHADOWED-SAME'

        # 2. what the console picks
        apk = None
        dump = None
        if not a.no_apk:
            for rel in APKS.get(resdir, []):
                p = os.path.join(out, rel)
                if os.path.exists(p):
                    apk = p
                    break
            if apk:
                dump = aapt2_dump(aapt2, apk)
        apk_cfgs = {}
        if dump is not None:
            ent = dump.get('%s/%s' % (t, name))
            if ent is None:
                apk_note = 'not in APK (built before this key was added?)'
            else:
                for c, raw in ent:
                    qq = parse_quals(c)
                    if qq is None:
                        continue
                    apk_cfgs[cfg_key(qq)] = (qq, apk_value(t, raw))
                mine = apk_cfgs.get(cfg_key(q))
                if mine is None:
                    apk_note = 'folder %s not in APK' % cfg_str(q)
                elif mine[1] != top[0][1] and not mine[1].startswith('(file'):
                    # the tree says another value should be there: stale build,
                    # a value format this script can't compare, or a layer it missed
                    apk_note = 'CHECK: APK %s has %s, tree says %s' % (
                        cfg_str(q), mine[1], top[0][1])
                elif mine[1] != val and not mine[1].startswith('(file'):
                    apk_note = 'APK %s has %s' % (cfg_str(q), mine[1])
        elif not a.no_apk:
            apk_note = 'no built APK for %s' % resdir

        # candidates: APK folders (base app included) + source layers (unbuilt keys)
        cands = {}
        for ck, (qq, v) in apk_cfgs.items():
            cands[ck] = qq
        for ck, lst in slots.items():
            cands.setdefault(ck, lst[0][2])
        # base app folders from the tree, for keys the APK doesn't show yet
        base_res = os.path.join(src, resdir)
        if not apk_cfgs:
            for bt, bn, bq, bv, bf, bp in scan_base(base_res, t, name):
                cands.setdefault(cfg_key(bq), bq)
        if aapt_sizes:
            cands = {ck: qq for ck, qq in cands.items()
                     if 'size' not in qq or qq['size'] in aapt_sizes}
        ckeys = list(cands)
        cl = [cands[k] for k in ckeys]
        lost_in = {}
        lost_q = {}
        for sname, dev in scenarios:
            if not matches(q, dev):
                continue    # our folder doesn't apply here (e.g. values-es in en-US)
            i = resolve(cl, dev)
            if i is None:
                continue
            wk = ckeys[i]
            if wk == cfg_key(q):
                continue
            # which layer provides the winning folder, and its value
            if wk in slots:
                wli, wval = slots[wk][0][0], slots[wk][0][1]
                who = layers[wli][0]
                if who.startswith(OURS_PREFIX):
                    continue    # our own, more specific folder (a translation, values-land...)
            else:
                who = 'base app'
                wval = apk_cfgs[wk][1] if wk in apk_cfgs else '?'
            if wval == val:
                continue
            lost_in.setdefault((cfg_str(cands[wk]), who, wval), []).append(sname)
            lost_q[cfg_str(cands[wk])] = cands[wk]
        for (wc, who, wval), where in sorted(lost_in.items()):
            locs = sorted({w.split(',')[0] for w in where})
            nights = sorted({w.split(',')[1] for w in where})
            where_s = ('all our locales' if len(locs) == len(LOCALES) else ' '.join(locs))
            where_s += '' if len(nights) == 2 else ' (%s only)' % nights[0]
            locale = 'lang' in lost_q[wc] and lost_q[wc].get('lang') != q.get('lang') \
                or ('region' in lost_q[wc] and 'region' not in q)
            problems.append('LOSES to %s from %s = %s in %s%s' % (
                wc, who, wval, where_s,
                '  (language/region folder: l10n-regions.py, O2-15/O2-101)' if locale else ''))
            if status in ('OK', 'SHADOWED-SAME'):
                status = 'LOSES-LOCALE' if locale else 'LOSES-QUALIFIER'
            elif status == 'LOSES-LOCALE' and not locale:
                status = 'LOSES-QUALIFIER'
        if status == 'LOSES-LOCALE' and a.skip_locale:
            status = 'OK-LOCALE'
        if status.startswith('LOSES'):
            losers += 1
        rows.append((status, resdir, t, name, cfg_str(q), val, layer, problems, apk_note))

    shown = 0
    for status, resdir, t, name, c, val, layer, problems, apk_note in rows:
        if status == 'OK' and not a.all and not apk_note.startswith('CHECK'):
            continue
        shown += 1
        v = val if len(val) <= 60 else val[:57] + '...'
        print('%-15s %s  %s/%s %s = %s  [%s]' % (status, resdir, t, name, c, v,
                                                  layer.replace(OURS_PREFIX, '')))
        for p in problems:
            print('                  ' + p)
        if apk_note:
            print('                  built: ' + apk_note)
    if a.tsv:
        with open(a.tsv, 'w', encoding='utf-8') as fh:
            fh.write('status\tresdir\ttype\tname\tconfig\tours\tlayer\tproblems\tbuilt\n')
            for status, resdir, t, name, c, val, layer, problems, apk_note in rows:
                fh.write('\t'.join([status, resdir, t, name, c, val.replace('\t', ' ').replace('\n', ' '),
                                    layer, ' | '.join(problems), apk_note]) + '\n')
    print('%d keys checked, %d lose, %d listed' % (len(rows), losers, shown))
    return 1 if losers else 0


_base = {}


def scan_base(resdir, t, name):
    """Folders of the base app that define t/name (read once per res dir)."""
    if resdir not in _base:
        idx = {}
        for e in scan_resdir(resdir):
            idx.setdefault((e[0], e[1]), []).append(e)
        _base[resdir] = idx
    return _base[resdir].get((t, name), [])


if __name__ == '__main__':
    sys.exit(main())
