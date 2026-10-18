#!/usr/bin/env python3
"""Checks a Daijishou library made by the setup (setup/internal/setup/daijishou_test.go) the way
Daijishou 1.5.0 would read it, and compares it with what daijishou-seed.txt says it
should contain for the fake card and cores.

Usage: checklib.py <seed.txt> <schema.sql> <Daijishou.db> <prefs.xml> <urigrants.xml>
                   <easyroms dir> <cores dir> <installed apps, comma separated>
"""

import json
import os
import re
import sqlite3
import sys
import xml.etree.ElementTree as ET

TREE = "content://com.android.externalstorage.documents/tree/"
PKG = "com.magneticchen.daijishou"

errors = []


def check(cond, msg):
    if not cond:
        errors.append(msg)


def sql_eval(expr):
    """Value of one SQL literal expression from the seed file, via sqlite itself."""
    return sqlite3.connect(":memory:").execute("SELECT " + expr).fetchone()


def expected(seed, root, cores, apps):
    """[(unique_id, [folders on the card], [player unique ids])] like dj_sql."""
    out, cur = [], None
    for line in open(seed, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if f[0] == "platform":
            cur = [f[1], [x for x in f[2].split(",") if os.path.isdir(os.path.join(root, x))],
                   [], sql_eval(f[3].split(",")[0])[0]]
            out.append(cur)
        elif f[0] == "player" and cur and f[1] == cur[0]:
            kind, _, name = f[2].partition(":")
            ok = (os.path.isfile(os.path.join(cores, name + "_libretro_android.so"))
                  if kind == "core" else name in apps)
            if ok:
                cur[2].append(sql_eval(f[3].split(",")[0])[0])
    return [(p[3], p[1], p[2]) for p in out if p[1] and p[2]]


def main(seed, schema, db, prefs, grants, root, cores, apps):
    apps = [a for a in apps.split(",") if a]
    uuid = os.path.basename(root)
    con = sqlite3.connect(db)
    check(con.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "integrity")
    check(con.execute("PRAGMA user_version").fetchone()[0] == 17, "user_version")
    h = re.search(r"VALUES\(42, '([0-9a-f]+)'\)", open(schema).read()).group(1)
    check(con.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()
          == (h,), "identity hash")

    # same tables and columns as a database made from the schema alone
    ref = sqlite3.connect(":memory:")
    ref.executescript(open(schema).read())
    for (t,) in ref.execute("SELECT name FROM sqlite_master WHERE type='table'"):
        check(ref.execute("PRAGMA table_info(`%s`)" % t).fetchall()
              == con.execute("PRAGMA table_info(`%s`)" % t).fetchall(), "table " + t)

    con.row_factory = sqlite3.Row
    players = {r["id"]: r for r in con.execute("SELECT * FROM PlayerEntity")}
    platforms = list(con.execute("SELECT * FROM PlatformEntity ORDER BY id"))
    uris = []
    got = []
    for p in platforms:
        name = p["unique_id"]
        for col in ("name", "shortname", "accept_filename_regex", "player_id_list",
                    "scraper_source_list", "items_sync_tree_uri_list",
                    "retro_achievements_console_id_list", "extra"):
            check(p[col] is not None, "%s.%s is NULL" % (name, col))
        ids = json.loads(p["player_id_list"])
        check(all(isinstance(i, int) for i in ids) and ids, name + " player_id_list")
        check(p["default_player_id"] == ids[0], name + " default player")
        check(all(isinstance(s, str) for s in json.loads(p["scraper_source_list"])),
              name + " scraper_source_list")
        check(all(isinstance(i, int) for i in json.loads(p["retro_achievements_console_id_list"])),
              name + " retro_achievements_console_id_list")
        re.compile(p["accept_filename_regex"])
        tree = json.loads(p["items_sync_tree_uri_list"])
        folders = []
        for u in tree:
            m = re.match(re.escape(TREE + uuid + "%3A") + r"([a-z0-9-]+)$", u)
            check(m is not None, name + " bad uri " + u)
            if m:
                folders.append(m.group(1))
                check(os.path.isdir(os.path.join(root, m.group(1))), name + " no folder " + u)
        uris += tree
        pl = []
        for i in ids:
            r = players.get(i)
            check(r is not None, "%s: no player %d" % (name, i))
            if r is None:
                continue
            pl.append(r["unique_id"])
            a = r["am_start_arguments"]
            check("\n" in a and "{file." in a, r["name"] + " arguments")
            re.compile(r["accepted_filename_regex"])
            m = re.search(r"-e LIBRETRO (\S+)", a)
            if m:
                check(os.path.isfile(os.path.join(cores, m.group(1) + "_libretro_android.so")),
                      r["name"] + " core missing")
            check(r["player"] in (1, 2), r["name"] + " player type")
        got.append((name, folders, pl))
    check(len(players) == sum(len(json.loads(p["player_id_list"])) for p in platforms),
          "players not used by exactly one platform")

    want = expected(seed, root, cores, apps)
    if got != want:
        errors.append("library differs:\n  got  %s\n  want %s" % (got, want))

    # every folder in the library is granted to Daijishou like the file picker does
    g = ET.parse(grants).getroot()
    check(g.tag == "uri-grants", "grants root")
    granted = {}
    for e in g:
        check(e.tag == "uri-grant", "grant tag")
        for a in ("sourceUserId", "targetUserId", "sourcePkg", "targetPkg", "uri",
                  "prefix", "modeFlags", "createdTime"):
            check(a in e.attrib, "grant without " + a)
        if e.get("targetPkg") == PKG:
            granted[e.get("uri")] = e.attrib
    for u in uris:
        a = granted.get(u)
        check(a is not None, "not granted: " + u)
        if a:
            check(a["sourcePkg"] == "com.android.externalstorage" and a["prefix"] == "true"
                  and a["modeFlags"] == "3" and a["sourceUserId"] == "0"
                  and a["targetUserId"] == "0" and a["createdTime"].isdigit(), "grant " + u)

    p = ET.parse(prefs).getroot()
    check(p.tag == "map", "prefs root")
    b = [e for e in p if e.tag == "boolean" and e.get("name") == "sync_entirely_on_start"]
    check(len(b) == 1 and b[0].get("value") == "true", "sync_entirely_on_start")

    for e in errors:
        print("  " + e)
    print("  library: %d platforms, %d players" % (len(platforms), len(players)))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:9]))
