#!/usr/bin/env python3
"""Exercise the G1 driver with fake Android commands and a temporary state tree.

Only this temporary driver copy changes its fixed base/shell/node preflight.
No Android/device commands run, and no real DRM node or service is touched.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

source = Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory(prefix="portmaster-driver-") as tmp:
    root = Path(tmp)
    mock = root / "mock"
    mock.mkdir()
    dispatch = mock / "dispatch"
    dispatch.write_text('''#!/usr/bin/env python3
import os, pathlib, sys
root = pathlib.Path(os.environ["MOCK_STATE"])
name = pathlib.Path(sys.argv[0]).name
if name == "id":
    print("0" if "-u" in sys.argv else "uid=0 synthetic_context")
elif name == "getprop":
    if len(sys.argv) == 1:
        for f in root.glob("init.svc.*"): print(f.name + "=" + f.read_text())
    else:
        f = root / sys.argv[1]
        print(f.read_text().strip() if f.exists() else "")
elif name == "setprop":
    op, svc = sys.argv[1:]
    with (root / "events").open("a") as out: out.write(op + " " + svc + "\\n")
    (root / ("init.svc." + svc)).write_text("running" if op == "ctl.start" else "stopped")
elif name == "pidof": sys.exit(1)
elif name in ["getenforce", "logcat", "dmesg"]: print("SYNTHETIC " + name)
elif name == "kms-probe":
    live = (root / "init.svc.surfaceflinger").read_text() == "running"
    if "--check-master" in sys.argv and live and not os.environ.get("BAD_BASELINE"):
        print("FAIL SET_MASTER (owner busy, permission denied, or unsupported): errno=22 (Invalid argument)")
        print("DRM closed\\nEXIT status=3")
        sys.exit(3)
    if "--seconds" in sys.argv and os.environ.get("DRAW_FAIL"):
        print("RESTORE fallback CRTC disabled\\nMASTER dropped\\nDRM closed\\nEXIT status=7")
        sys.exit(7)
    print("MASTER acquired device=/dev/dri/card0\\nMASTER dropped\\nDRM closed\\nEXIT status=0")
''')
    dispatch.chmod(0o755)
    for command in ["id", "getprop", "setprop", "pidof", "getenforce", "logcat", "dmesg"]:
        (mock / command).symlink_to(dispatch)
    for case, expected in [("normal", 0), ("fallback", 7), ("bad-baseline", 1)]:
        state = root / case
        state.mkdir()
        (state / "init.svc.surfaceflinger").write_text("running")
        (state / "init.svc.vendor.hwcomposer-2-1").write_text("running")
        for name in ["launch-flow.sh", "handoff-watchdog.sh"]:
            shutil.copyfile(source / name, state / name)
        (state / "kms-probe").symlink_to(dispatch)
        text = (source / "hardware-test.sh").read_text()
        text = text.replace("base=/data/local/tmp/portmaster", "base=" + str(state))
        text = text.replace("shell=/system/bin/sh", "shell=/bin/sh")
        text = text.replace("[ -c /dev/dri/card0 ]", "[ -c /dev/null ]")
        driver = state / "hardware-test.sh"
        driver.write_text(text)
        env = dict(os.environ, PATH=str(mock) + ":" + os.environ["PATH"], MOCK_STATE=str(state))
        if case == "fallback": env["DRAW_FAIL"] = "1"
        if case == "bad-baseline": env["BAD_BASELINE"] = "1"
        result = subprocess.run(["sh", str(driver), "--run"], env=env,
                                capture_output=True, text=True, timeout=25)
        assert result.returncode == expected, result.stdout + result.stderr
        run, = (state / "runs").iterdir()
        assert (run / "result-template.md").exists()
        assert not (state / "hardware.lock").exists()
        assert not (run / "guard.ready").exists()
        assert "visible=UNASSESSED" in result.stdout
        if case == "bad-baseline":
            assert not (state / "events").exists()
        else:
            assert (run / "guard.cancel").exists()
            assert (state / "events").read_text().splitlines() == [
                "ctl.stop surfaceflinger", "ctl.stop vendor.hwcomposer-2-1",
                "ctl.start vendor.hwcomposer-2-1", "ctl.start surfaceflinger"]
        print("PASS synthetic-driver=" + case)
print("Only temporary copies/fake services were exercised; no hardware experiment ran.")
