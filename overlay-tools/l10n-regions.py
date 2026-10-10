#!/usr/bin/env python3
"""Keep our resource overlays from being shadowed by region folders of the app.

A static overlay (PRODUCT_/DEVICE_PACKAGE_OVERLAYS) only replaces a resource
for the folders (configs) the overlay itself has. If Settings has
values-es-rUS/about_settings and we only overlay values-es, the stock es-rUS
text stays, and on a Latin American Spanish locale Android picks it over ours
(es-US and es-MX count as es-419, so all of Latin America gets es-rUS).
English is the same: values-en-rGB beats our values/ for en-GB, en-AU, en-IN
and every other en-001 country (only en-US and near-US countries use values/).

  l10n-regions.py            check: list shadowed keys, exit 1 if any
  l10n-regions.py --write    (re)write the generated region folders

The generated files are named GENERATED_NAME. They hold only the keys the
app's region folder would otherwise win for, copied from our language folder
(values-es -> values-es-rUS, values/ -> values-en-rGB, values-pt -> values-pt-rPT,
values-zh-rTW -> values-zh-rHK). Edit the language folder, then re-run --write.
A hand-written file in a region folder wins over the generator for its keys.

Traditional Chinese (zh-rTW, zh-rHK, zh-rMO) is not copied from values-zh-rCN
(that is Simplified). It needs a hand-written values-zh-rTW; without one it is
reported as not translated, not as an error.
"""
import argparse
import functools
import os
import re
import sys
import xml.dom.minidom

GENERATED_NAME = "andr36oid_regions_generated.xml"
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)  # device/gameconsole/common
OUR_ROOTS = ["overlay", "overlay-lineage", "overlay-wallpaper"]
# Product overlays of the tree; they sit above our device overlays.
VENDOR_ROOTS = ["vendor/lineage/overlay/common", "vendor/crowdin/overlay"]
# Our 9 languages (Android folder names). "en" is our values/ folder.
OUR_LANGS = {"en", "es", "pt", "ru", "de", "fr", "pl", "tr", "in", "zh-Hans"}
# UI modes the console never has: folders with these never apply.
NEVER = {"watch", "car", "television", "vrheadset", "appliance", "desk"}
HANT_REGIONS = {"TW", "HK", "MO"}
VALUE_TAGS = {"string", "plurals", "string-array", "array", "integer-array",
              "bool", "integer", "dimen", "color", "fraction", "item"}


def parse_folder(name):
    """values-es-rUS-nokeys -> (('es', 'US'), ('nokeys',)); values -> (None, ())."""
    parts = name.split("-")[1:]
    i = 0
    while i < len(parts) and re.match(r"^(mcc|mnc)\d+$", parts[i]):
        i += 1
    loc = None
    if i < len(parts) and parts[i].startswith("b+"):
        sub = parts[i].split("+")[1:]
        lang, region = sub[0], ""
        for s in sub[1:]:
            if re.match(r"^([A-Z]{2}|\d{3})$", s):
                region = s
            elif s == "Hant" and not region:
                region = "TW"  # treat zh-Hant like zh-TW for our purposes
        loc = (lang, region)
        i += 1
    elif i < len(parts) and re.match(r"^[a-z]{2,3}$", parts[i]):
        lang, region = parts[i], ""
        i += 1
        if i < len(parts) and re.match(r"^r([A-Z]{2}|\d{3})$", parts[i]):
            region = parts[i][1:]
            i += 1
        loc = (lang, region)
    return loc, tuple(parts[i:])


def family(loc):
    """Language 'family' a locale belongs to; zh splits by script."""
    if loc is None:
        return None
    lang, region = loc
    if lang == "zh":
        return "zh-Hant" if region in HANT_REGIONS else "zh-Hans"
    return lang


def keys_in(path):
    """{(type, name): [raw xml snippet, ...]} for one values xml file."""
    out = {}
    try:
        with open(path, encoding="utf-8") as f:
            raw = f.read()
    except (OSError, UnicodeDecodeError):
        return out
    raw_nc = re.sub(r"<!--.*?-->", lambda m: " " * len(m.group(0)), raw, flags=re.S)
    depth = 0  # depth inside <resources>
    for m in re.finditer(r"<(/?)([a-zA-Z:_-]+)\b([^>]*?)(/?)>", raw_nc):
        endtag, tag, attrs, selfclose = m.group(1), m.group(2), m.group(3), m.group(4)
        if tag == "resources":
            continue
        if endtag:
            depth -= 1
            continue
        top = depth == 0
        if not selfclose:
            depth += 1
        if not top or tag not in VALUE_TAGS:
            continue
        close = "/>" if selfclose else ">"
        nm = re.search(r'\bname="([^"]+)"', attrs)
        if not nm:
            continue
        rtype = tag
        if tag == "item":
            t = re.search(r'\btype="([^"]+)"', attrs)
            rtype = t.group(1) if t else "item"
        elif tag in ("array", "integer-array", "string-array"):
            rtype = "array"
        if close == "/>":
            end = m.end()
        else:
            e = raw_nc.find("</%s>" % tag, m.end())
            end = e + len("</%s>" % tag) if e >= 0 else m.end()
        out.setdefault((rtype, nm.group(1)), []).append(raw[m.start():end])
    return out


@functools.lru_cache(maxsize=None)
def scan_res(resdir, skip_generated=False):
    """{folder: {key: [(file, snippet)]}} for all values* folders."""
    res = {}
    if not os.path.isdir(resdir):
        return res
    for d in sorted(os.listdir(resdir)):
        if not (d == "values" or d.startswith("values-")):
            continue
        full = os.path.join(resdir, d)
        if not os.path.isdir(full):
            continue
        for fn in sorted(os.listdir(full)):
            if not fn.endswith(".xml"):
                continue
            if skip_generated and fn == GENERATED_NAME:
                continue
            for k, snips in keys_in(os.path.join(full, fn)).items():
                res.setdefault(d, {}).setdefault(k, []).extend((fn, s) for s in snips)
    return res


def norm(snippet):
    return re.sub(r"\s+", " ", re.sub(r'\s(name|msgid)="[^"]*"', "", snippet)).strip()


def base_values(layers, folder, key):
    vals = set()
    for layer in layers:
        for _, s in scan_res(layer).get(folder, {}).get(key, []):
            vals.add(norm(s))
    return vals


def our_resdirs():
    for root in OUR_ROOTS:
        base = os.path.join(REPO, root)
        for dp, dns, _ in os.walk(base):
            if any(d == "values" or d.startswith("values-") for d in dns):
                yield root, os.path.relpath(dp, base)
                dns[:] = []


def source_folder(ours, target_loc, rest):
    """Our folder that holds the text a region folder should get."""
    lang, region = target_loc
    suffix = "".join("-" + r for r in rest)
    cands = []
    if lang == "zh":
        if region in HANT_REGIONS:
            cands = ["values-zh-rTW"]
        else:
            cands = ["values-zh-rCN"]
    elif lang == "en":
        cands = ["values-en", "values"]
    elif lang == "pt" and region != "BR":
        cands = ["values-pt", "values-pt-rBR"]
    else:
        cands = ["values-" + lang]
    for c in cands:
        if c + suffix in ours:
            return c + suffix
        if c in ours:
            return c
    return None


def analyse(src):
    findings = []  # (level, text)
    plan = {}      # generated file path -> content
    for root, rel in our_resdirs():
        our_dir = os.path.join(REPO, root, rel)
        ours_all = scan_res(our_dir)
        ours = scan_res(our_dir, skip_generated=True)
        base_layers = [os.path.join(src, rel)] + [os.path.join(src, v, rel) for v in VENDOR_ROOTS]
        base = {}
        for layer in base_layers:
            for folder, keys in scan_res(layer).items():
                for k in keys:
                    base.setdefault(folder, set()).add(k)
        our_keys = set()
        for keys in ours.values():
            our_keys |= set(keys)
        # Product overlays (vendor/lineage, crowdin) come before our device
        # overlays, so the same key in the same folder there beats ours.
        if root != "overlay-wallpaper":
            for v in VENDOR_ROOTS:
                for folder, keys in scan_res(os.path.join(src, v, rel)).items():
                    for k in sorted(set(keys) & set(ours_all.get(folder, {}))):
                        theirs = {norm(sn) for _, sn in keys[k]}
                        mine = {norm(sn) for _, sn in ours_all[folder][k]}
                        if theirs == mine:
                            continue
                        # Not a language problem, so a warning: our value is unused.
                        findings.append(("WARN", "%s: %s/%s/%s is a product overlay with another value; it beats ours (ours is unused)" % (
                            rel, v, folder, k[1])))
        langs_we_have = {}
        for folder in ours:
            loc, rest = parse_folder(folder)
            fam = family(loc) if loc else "en"
            langs_we_have.setdefault(fam, set()).add(folder)
        gen = {}  # folder -> [snippets]
        for bfolder in sorted(base):
            bloc, brest = parse_folder(bfolder)
            if NEVER & set(brest):
                continue
            hit = base[bfolder] & our_keys
            if not hit:
                continue
            bfam = family(bloc) if bloc else "en"
            for k in sorted(hit):
                if k in ours.get(bfolder, {}):
                    continue  # we define it in exactly this folder
                # which of our folders would the user otherwise get?
                src_folder = source_folder(ours, bloc, brest) if bloc else None
                if bloc is None:
                    # Base folder with other qualifiers (values-land, values-large):
                    # not a language problem. Whether it applies depends on the
                    # screen, so only note it, and only if the value differs.
                    if k in ours.get("values", {}) and brest:
                        bval = base_values(base_layers, bfolder, k)
                        oval = norm(ours["values"][k][0][1])
                        if bval and oval not in bval:
                            findings.append(("NOTE", "%s: %s/%s differs from our values/ one and wins if %s applies on the screen" % (
                                rel, bfolder, k[1], "-".join(brest))))
                    continue
                we_cover = [f for f in langs_we_have.get(bfam, ()) if k in ours.get(f, {})]
                if bfam == "en":
                    we_cover += [f for f in ours if parse_folder(f)[0] is None and k in ours[f]]
                if not we_cover:
                    # we do not translate this key into this language at all
                    plain = bloc[1] == "" or (bloc[0] == "zh" and bloc[1] in ("CN", "TW"))
                    if plain and not brest:
                        lvl = "INFO"
                        if bfam in OUR_LANGS and k[0] == "string":
                            lvl = "WARN"
                        elif k[0] != "string":
                            lvl = "FAIL"
                        findings.append((lvl, "%s: %s has its own %s, we give no %s value (stock text shows)" % (
                            rel, bfolder, k[1], bfam)))
                    elif bfam == "zh-Hant":
                        findings.append(("INFO", "%s: %s has %s, we have no Traditional Chinese (values-zh-rTW)" % (
                            rel, bfolder, k[1])))
                    continue
                if not bloc[1] and not brest:
                    continue  # plain language folder, and we cover the language
                if src_folder is None or k not in ours.get(src_folder, {}):
                    findings.append(("FAIL", "%s: %s/%s shadows ours and no source folder has it" % (
                        rel, bfolder, k[1])))
                    continue
                gen.setdefault(bfolder, []).append((k, src_folder, [s for _, s in ours[src_folder][k]]))
        for folder, items in sorted(gen.items()):
            path = os.path.join(REPO, root, rel, folder, GENERATED_NAME)
            lines = ['<?xml version="1.0" encoding="utf-8"?>',
                     "<!-- GENERATED by overlay-tools/l10n-regions.py (write mode). Do not edit.",
                     "     The app has its own %s folder, which would beat our language folder" % folder,
                     "     for these keys. Edit the source folder named below and re-run. -->",
                     '<resources xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">']
            for k, sf, snips in items:
                lines.append("    <!-- from %s -->" % sf)
                lines.extend("    " + s for s in snips)
            lines.append("</resources>")
            plan[path] = "\n".join(lines) + "\n"
            for k, sf, _ in items:
                have = ours_all.get(folder, {}).get(k)
                findings.append(("FIX" if have else "FAIL", "%s: %s/%s would win over our %s (%s)" % (
                    rel, folder, k[1], sf, "fixed by generated copy" if have else "NOT fixed, run --write")))
        # generated files that should no longer exist
        for folder in ours_all:
            p = os.path.join(REPO, root, rel, folder, GENERATED_NAME)
            if os.path.exists(p) and p not in plan:
                with open(p, encoding="utf-8") as f:
                    srcs = set(re.findall(r"<!-- from (values[^ ]*) -->", f.read()))
                missing = sorted(sf for sf in srcs if sf not in ours)
                if missing:
                    # e.g. this branch without the translations branch yet
                    findings.append(("WARN", "%s: %s/%s: source %s not here (merge its translations first)" % (
                        rel, folder, GENERATED_NAME, " ".join(missing))))
                    continue
                findings.append(("FAIL", "%s: %s/%s is stale (no longer needed), run --write" % (rel, folder, GENERATED_NAME)))
                plan[p] = None
    return findings, plan


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", help="Android tree root (default: three levels above this repo)")
    ap.add_argument("--write", action="store_true", help="write the generated region folders")
    ap.add_argument("-q", "--quiet", action="store_true", help="only print WARN/FAIL")
    a = ap.parse_args()
    src = a.src or os.path.normpath(os.path.join(REPO, "..", "..", ".."))
    if not os.path.isdir(os.path.join(src, "frameworks", "base")):
        sys.exit("no Android tree at %s (use --src)" % src)
    findings, plan = analyse(src)
    bad = 0
    for path, content in sorted(plan.items()):
        cur = None
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                cur = f.read()
        if cur == content:
            continue
        if a.write:
            if content is None:
                os.remove(path)
                d = os.path.dirname(path)
                if not os.listdir(d):
                    os.rmdir(d)
                print("removed", os.path.relpath(path, REPO))
            else:
                xml.dom.minidom.parseString(content.encode("utf-8"))
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(content)
                print("wrote", os.path.relpath(path, REPO))
        elif content is not None:
            print("FAIL %s is missing or out of date, run --write" % os.path.relpath(path, REPO))
            bad += 1
    if a.write:
        scan_res.cache_clear()
        findings, plan = analyse(src)
    other = [t for l, t in findings if l == "INFO" and "we give no" in t]
    findings = [(l, t) for l, t in findings if not (l == "INFO" and "we give no" in t)]
    if other:
        langs = sorted({re.search(r"we give no (\S+) value", t).group(1) for t in other})
        findings.append(("INFO", "%d keys x languages we do not translate at all (stock text shows, not a region problem): %s" % (
            len(other), " ".join(langs))))
    for lvl, text in findings:
        if lvl == "FAIL" and not a.write:
            bad += 1
        if a.quiet and lvl in ("INFO", "FIX"):
            continue
        print(lvl, text)
    if a.write:
        bad = sum(1 for lvl, _ in findings if lvl == "FAIL")
    print("%d problem(s)" % bad)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
