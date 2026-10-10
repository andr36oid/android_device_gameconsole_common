#!/bin/sh
# Links a tiny app the way the build links Settings with our static overlay:
# base = the stock about_settings entries of Settings (all its locale folders),
# overlay = our overlay/packages/apps/Settings/res. Then dumps the result and
# checks that every locale folder of the base now has our text, i.e. no stock
# region folder (values-es-rUS, values-en-rGB...) is left over.
#   test-l10n-regions.sh <android-tree> [aapt2]
set -eu
SRC=${1:?usage: $0 <android-tree> [aapt2]}
AAPT2=${2:-$SRC/out/host/linux-x86/bin/aapt2}
HERE=$(cd "$(dirname "$0")" && pwd)
OVL=$HERE/../overlay/packages/apps/Settings/res
T=$(mktemp -d)
[ -n "$T" ] && [ -d "$T" ] || exit 1
trap 'rm -rf "$T"' EXIT

# Base: only about_settings from each stock folder, so the link needs no deps.
python3 - "$SRC/packages/apps/Settings/res" "$T/base" <<'EOF'
import os, re, sys
src, dst = sys.argv[1], sys.argv[2]
for d in sorted(os.listdir(src)):
    f = os.path.join(src, d, "strings.xml")
    if not (d.startswith("values") and os.path.isfile(f)):
        continue
    raw = open(f, encoding="utf-8").read()
    ent = re.findall(r'<string name="about_settings"[^>]*>.*?</string>', raw, re.S)
    if not ent:
        continue
    os.makedirs(os.path.join(dst, d))
    with open(os.path.join(dst, d, "strings.xml"), "w", encoding="utf-8") as o:
        o.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + "\n".join(ent) + "\n</resources>\n")
EOF

mkdir -p "$T/fb" "$T/fo"
"$AAPT2" compile --dir "$T/base" -o "$T/fb/base.zip"
# Only our string files; config.xml keys are not in this tiny base.
for f in "$OVL"/values*/*.xml; do
    grep -q '<string ' "$f" || continue
    "$AAPT2" compile -o "$T/fo" "$f"
done
cat > "$T/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.android.settings"/>
EOF
set --
for f in "$T"/fo/*.flat; do set -- "$@" -R "$f"; done
"$AAPT2" link --manifest "$T/AndroidManifest.xml" -o "$T/out.apk" --product tablet \
    "$T/fb/base.zip" "$@"
"$AAPT2" dump resources "$T/out.apk" > "$T/dump.txt"

# Every config of about_settings: fail if a region folder kept the stock text
# while its language folder has ours (the region shadows our text).
python3 - "$T/dump.txt" "$OVL" <<'EOF'
import os, re, sys
dump, ovl = sys.argv[1], sys.argv[2]
ours = set()
for d in os.listdir(ovl):
    for fn in os.listdir(os.path.join(ovl, d)):
        raw = open(os.path.join(ovl, d, fn), encoding="utf-8").read()
        for m in re.finditer(r'<string name="about_settings"[^>]*>(.*?)</string>', raw, re.S):
            ours.add(m.group(1).strip().strip('"'))
lines = open(dump, encoding="utf-8").read().splitlines()
vals = {}
inres = False
for ln in lines:
    if "string/about_settings" in ln:
        inres = True
        continue
    if inres:
        m = re.match(r'\s+\((.*?)\) "(.*)"$', ln)
        if not m:
            if not ln.startswith(" "):
                break
            continue
        vals[m.group(1) or "default"] = m.group(2)

def parent(cfg):
    """The folder a region folder falls back to, None for plain ones."""
    if cfg == "default" or "-r" not in cfg:
        return None
    lang, region = cfg.split("-r", 1)
    if lang == "en":
        return "default"
    if lang == "zh":
        return "zh-rTW" if region in ("HK", "MO") else None
    return lang

bad = 0
for cfg in sorted(vals):
    val = vals[cfg]
    p = parent(cfg)
    if val in ours:
        print("ours      %-10s %s" % (cfg, val))
    elif p and vals.get(p) in ours:
        bad += 1
        print("SHADOWED  %-10s %s  (our %s text is lost here)" % (cfg, val, p))
    else:
        print("stock     %-10s %s" % (cfg, val))
print("%d region config(s) show stock text although we translate the language" % bad)
sys.exit(1 if bad else 0)
EOF
