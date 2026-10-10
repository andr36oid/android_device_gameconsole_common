#!/usr/bin/env python3
"""Checks daijishou/schema.sql against Daijishou.apk: every SQL statement (and the Room
identity hash in it) must be a string constant in the APK's dex, i.e. the schema is
exactly the one this Daijishou creates. Fails after a Daijishou update that changed its
library schema: then take the new createAllTables statements (jadx on the APK,
DaijishouDatabase_Impl), the new identity hash and version, and bump
DAIJISHOU_VERSION_CODE in scripts/gen-daijishou.py."""

import re
import sys
import zipfile


def main(apk, schema):
    with zipfile.ZipFile(apk) as z:
        dex = b"".join(z.read(n) for n in z.namelist() if re.match(r"classes\d*\.dex$", n))
    ok = True
    for line in open(schema, encoding="utf-8"):
        line = line.strip()
        if not line or line.startswith("--") or line.startswith("PRAGMA"):
            continue
        stmt = line.rstrip(";")
        if stmt.startswith("INSERT OR REPLACE INTO room_master_table"):
            h = re.search(r"'([0-9a-f]{32})'", stmt).group(1)
            # Room generates this insert with the hash in it
            if stmt.encode() not in dex or h.encode() not in dex:
                print("identity hash %s not in the APK" % h)
                ok = False
            continue
        if stmt.encode() not in dex:
            print("not in the APK: " + stmt[:100])
            ok = False
    m = re.search(r"PRAGMA user_version = (\d+);", open(schema, encoding="utf-8").read())
    if not m:
        print("no user_version in the schema")
        ok = False
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
