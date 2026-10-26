#!/usr/bin/env python3
"""Compile the draft against a TEMPORARY copy of existing Android policy only."""
from pathlib import Path
import os
import subprocess
import tempfile

src = Path(os.environ.get("SRC_ROOT", "/andr36oid/src"))
policy = src / "system/sepolicy/public"
draft = Path(__file__).resolve().parents[1] / "portmaster.te"
baseline = src / "out/target/product/r36s/obj/FAKE/sepolicy_neverallows_intermediates/policy.conf"
compiler = src / "out/host/linux-x86/bin/checkpolicy"
text = baseline.read_text()
expanded = subprocess.check_output(
    ["m4", str(policy / "global_macros"), str(policy / "te_macros"), str(draft)], text=True)
marker = '#line 1 "system/sepolicy/private/roles_decl"'
assert text.count(marker) == 1
text = text.replace(marker, expanded + "\n" + marker)
changes = [
    ("allow { domain -hwservicemanager -vndservicemanager } binder_device:",
     "allow { domain -hwservicemanager -vndservicemanager -portmaster_guest } binder_device:"),
    ("allow { domain -servicemanager -vndservicemanager -isolated_app } hwbinder_device:",
     "allow { domain -servicemanager -vndservicemanager -isolated_app -portmaster_guest } hwbinder_device:"),
    ("-zygote\n} { fs_type -sdcard_type }:filesystem { mount remount relabelfrom relabelto };",
     "-zygote\n    -portmaster\n} { fs_type -sdcard_type }:filesystem { mount remount relabelfrom relabelto };")]
with tempfile.TemporaryDirectory(prefix="portmaster-policy-") as tmp:
    conf, binary = Path(tmp) / "policy.conf", Path(tmp) / "policy.bin"
    conf.write_text(text)
    args = [str(compiler), "-M", "-c", "30", "-o", str(binary), str(conf)]
    result = subprocess.run(args, capture_output=True, text=True)
    assert result.returncode != 0 and "neverallow" in result.stderr, result.stderr
    print("PASS unchanged policy rejects mount/binder draft as expected")
    for before, after in changes:
        assert text.count(before) == 1, before
        text = text.replace(before, after)
    conf.write_text(text)
    result = subprocess.run(args, capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    print("PASS temporary composed policy compiles, neverallows enabled, policy version 30")
print("No production source, policy include, installed policy or device was changed.")
