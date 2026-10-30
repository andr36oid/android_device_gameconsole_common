#!/usr/bin/env python3
"""Host fault injection and mocked Android init. No device or network access."""
import os
from pathlib import Path
import subprocess
import tempfile

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent
HEADERS = Path(os.environ.get("SRC_ROOT", "/andr36oid/src")) / "kernel/gameconsole/r36s/include/uapi/drm"


def run(args, **kwargs):
    return subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **kwargs)


with tempfile.TemporaryDirectory(prefix="portmaster-checks-") as temp:
    root = Path(temp)
    test = root / "probe-faults"
    subprocess.run(["gcc", "-std=c99", "-Wall", "-Wextra", "-Werror", "-fsanitize=undefined",
                    "-I" + str(HEADERS), str(HERE / "probe-faults.c"), "-o", str(test)], check=True)
    scenarios = {"ok": 0, "check": 0, "open": 3, "master": 3, "resources": 4,
                 "connector": 4, "disconnected": 4, "encoder": 4, "save": 4,
                 "create": 5, "addfb": 5, "map_ioctl": 5, "mmap": 5,
                 "modeset": 6, "active": 0, "restore": 7, "vanished": 7,
                 "disable-fail": 7, "drop": 7, "close": 7, "signal": 143}
    for scenario, expected in scenarios.items():
        result = run([str(test), scenario, str(expected)])
        assert result.returncode == 0, result.stdout
        print(result.stdout.splitlines()[-1])

    mock = root / "bin"
    mock.mkdir()
    dispatcher = mock / "dispatch"
    dispatcher.write_text('''#!/usr/bin/env python3
import os, pathlib, signal, sys
root = pathlib.Path(os.environ["MOCK_STATE"])
name = pathlib.Path(sys.argv[0]).name
if name == "id":
    print("0")
elif name == "getprop":
    prop = sys.argv[1]
    f = root / prop
    print(f.read_text().strip() if f.exists() else "")
elif name == "setprop":
    op, svc = sys.argv[1:]
    with (root / "events").open("a") as log:
        log.write(op + " " + svc + "\\n")
    if os.environ.get("MOCK_STOP_FAIL") == svc and op == "ctl.stop":
        sys.exit(1)
    (root / ("init.svc." + svc)).write_text("running" if op == "ctl.start" else "stopped")
elif name == "kms-probe":
    with (root / "events").open("a") as log:
        log.write("probe " + " ".join(sys.argv[1:]) + "\\n")
    if "--check-master" in sys.argv and os.environ.get("MOCK_MASTER_FAIL"):
        sys.exit(3)
    if "--seconds" in sys.argv and os.environ.get("MOCK_DRAW_FAIL"):
        sys.exit(6)
    if "--seconds" in sys.argv and os.environ.get("MOCK_RESPAWN"):
        (root / "init.svc.surfaceflinger").write_text("running")
    if "--seconds" in sys.argv and os.environ.get("MOCK_SIGNAL_LAUNCH"):
        manager = int((root / "session.lock/manager").read_text())
        os.kill(manager, signal.SIGTERM)
elif name == "sleep":
    if os.environ.get("MOCK_CANCEL_GUARD"):
        (root / "guard.cancel").touch()
    if os.environ.get("MOCK_QUIESCE_GUARD") and not (root / "quiesced").exists():
        (root / "init.svc.surfaceflinger").write_text("stopped")
        (root / "init.svc.vendor.hwcomposer-2-1").write_text("stopped")
        (root / "quiesced").touch()
elif name == "pidof":
    if os.environ.get("MOCK_ACTIVE_PROBE"):
        print("424242")
    else:
        sys.exit(1)
''')
    dispatcher.chmod(0o755)
    for command in ["id", "getprop", "setprop", "kms-probe", "pidof"]:
        (mock / command).symlink_to(dispatcher)

    cases = ["success", "master-fail", "draw-fail", "stop-fail", "not-armed", "chroot-gated", "signal", "respawn", "active-probe"]
    for case in cases:
        state = root / case
        state.mkdir()
        (state / "init.svc.surfaceflinger").write_text("running")
        (state / "init.svc.vendor.hwcomposer-2-1").write_text("running")
        # Must preserve this originally stopped alternate service.
        (state / "init.svc.hwcomposer-2-1").write_text("stopped")
        env = dict(os.environ, PATH=str(mock) + ":" + os.environ["PATH"],
                   MOCK_STATE=str(state), PM_STATE_BASE=str(state), PM_PROBE=str(mock / "kms-probe"),
                   PM_SECONDS="1", PM_RECOVERY_ARMED="YES")
        if case == "master-fail": env["MOCK_MASTER_FAIL"] = "1"
        if case == "draw-fail": env["MOCK_DRAW_FAIL"] = "1"
        if case == "stop-fail": env["MOCK_STOP_FAIL"] = "vendor.hwcomposer-2-1"
        if case == "signal": env["MOCK_SIGNAL_LAUNCH"] = "1"
        if case == "respawn": env["MOCK_RESPAWN"] = "1"
        if case == "active-probe": env["MOCK_ACTIVE_PROBE"] = "1"
        if case == "not-armed": env.pop("PM_RECOVERY_ARMED")
        result = run(["sh", str(SOURCE / "launch-flow.sh"),
                      "--chroot" if case == "chroot-gated" else "--probe"], env=env, timeout=15)
        assert (result.returncode == 0) == (case == "success"), result.stdout
        events = (state / "events").read_text().splitlines() if (state / "events").exists() else []
        assert "ctl.start hwcomposer-2-1" not in events, events
        expected_state = "stopped" if case == "active-probe" else "running"
        assert (state / "init.svc.surfaceflinger").read_text().strip() == expected_state, result.stdout
        assert (state / "init.svc.vendor.hwcomposer-2-1").read_text().strip() == expected_state, result.stdout
        if case in ["not-armed", "chroot-gated"]:
            assert events == [], events
        if case in ["success", "master-fail", "draw-fail", "signal"]:
            assert events[:2] == ["ctl.stop surfaceflinger", "ctl.stop vendor.hwcomposer-2-1"], events
            assert events[-2:] == ["ctl.start vendor.hwcomposer-2-1", "ctl.start surfaceflinger"], events
        assert (state / "session.lock").exists() == (case == "active-probe"), result.stdout
        if case == "active-probe":
            assert not any(event.startswith("ctl.start") for event in events), events
        print("PASS launch=" + case)

    # Guard runs with synthetic time and process inventory. Never signal a host PID.
    for command in ["sleep"]:
        (mock / command).symlink_to(dispatcher)
    for case in ["cancelled", "expired", "active-probe"]:
        state = root / ("guard-" + case)
        state.mkdir()
        (state / "init.svc.surfaceflinger").write_text("running")
        (state / "init.svc.vendor.hwcomposer-2-1").write_text("running")
        (state / "init.svc.hwcomposer-2-1").write_text("stopped")
        env = dict(os.environ, PATH=str(mock) + ":" + os.environ["PATH"], MOCK_STATE=str(state))
        if case == "cancelled": env["MOCK_CANCEL_GUARD"] = "1"
        else: env["MOCK_QUIESCE_GUARD"] = "1"
        if case == "active-probe": env["MOCK_ACTIVE_PROBE"] = "1"
        result = run(["sh", str(SOURCE / "handoff-watchdog.sh"), str(state)], env=env, timeout=15)
        assert (result.returncode == 0) == (case != "active-probe"), result.stdout
        events = (state / "events").read_text().splitlines() if (state / "events").exists() else []
        if case == "expired":
            assert events == ["ctl.start vendor.hwcomposer-2-1", "ctl.start surfaceflinger"], events
            assert (state / "guard.expired").exists()
        else:
            assert not events, events
        assert (state / "guard.ready").exists() == (case == "active-probe")
        print("PASS guard=" + case)

print("All synthetic checks passed. These do not validate a real DRM driver or Android init.")
