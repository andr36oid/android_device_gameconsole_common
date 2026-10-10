#!/usr/bin/env python3
"""check-armv80.py <lib.so>... : checks that cores run on the ARMv8.0 Cortex-A35.
Counts ARMv8.1+ instructions (LSE atomics; also dot product, RDMA, RCpc, fp16
arithmetic) in executable sections. Ignores:
 - hits whose neighbours (+-2) decode as <unknown> (data tables inside .text)
 - compiler outline-atomics helpers: LSE op right after `cbz w16` (runtime
   __aarch64_have_lse_atomics check with an LL/SC fallback), safe on v8.0."""
import re, subprocess, sys
import os
OBJDUMP = os.environ.get("OBJDUMP", "llvm-objdump")
LSE = re.compile(r"^(ld(add|clr|eor|set|smax|smin|umax|umin)|st(add|clr|eor|set|smax|smin|umax|umin)|swp|casp?)(a|l|al)?(b|h)?$")
OTHER = re.compile(r"^(sdot|udot|sqrdmlah|sqrdmlsh|ldapr\w*|fcma\w*|fjcvtzs)$")
LINE = re.compile(r"^\s*[0-9a-f]+:\s+(?:[0-9a-f]{8}|(?:[0-9a-f]{2} ){4})\s*(\S+)\s*(.*)$")
def check(path):
    r = subprocess.run([OBJDUMP, "-d", "--mattr=+lse,+v8.2a,+dotprod,+rcpc,+fullfp16", path],
                       capture_output=True, text=True)
    out = r.stdout  # may exit non-zero on odd sections but still disassemble .text
    ins = []
    for l in out.splitlines():
        m = LINE.match(l)
        if m: ins.append((m.group(1), m.group(2), l.strip()))
    real, helper, table, other = [], 0, 0, []
    for i, (op, args, raw) in enumerate(ins):
        lse = LSE.match(op)
        oth = OTHER.match(op) or (op.startswith("f") and ".8h" in args and op not in ("fcvtn", "fcvtl", "fcvtl2", "fcvtn2"))
        if not (lse or oth):
            continue
        near = ins[max(0, i-2):i] + ins[i+1:i+3]
        if any(n[0] == "<unknown>" for n in near):
            table += 1; continue
        if lse and i > 0 and ins[i-1][0] == "cbz" and ins[i-1][1].startswith("w16"):
            helper += 1; continue
        (real if lse else other).append(raw)
    return len(ins), real, helper, table, other
bad = 0
for p in sys.argv[1:]:
    n, real, helper, table, other = check(p)
    status = "OK" if n and not real and not other else "FAIL"
    if not n: print(f"FAIL {p}: {OBJDUMP} could not disassemble it"); bad += 1; continue
    bad += status == "FAIL"
    print(f"{status} {p.split('/')[-1]}: {n} insns, LSE real={len(real)} outline-helper={helper} in-data={table}, other v8.1+={len(other)}")
    for r in (real + other)[:5]: print("   ", r)
sys.exit(1 if bad else 0)
