#!/usr/bin/env python3
"""Exercise the real boot checks on fake boards; Android calls use small host doubles."""
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

app = Path(__file__).resolve().parents[1]
jdk = Path("/andr36oid/src/prebuilts/jdk/jdk11/linux-x86/bin")

stubs = {
    "android/os/SystemProperties.java": """
package android.os;
public class SystemProperties {
 public static final java.util.Map<String,String> values=new java.util.HashMap<>();
 public static String get(String key,String fallback){return values.getOrDefault(key,fallback);}
}
""",
    "android/content/ComponentName.java": """
package android.content;
public class ComponentName {
    public final String pkg, name;
    public ComponentName(String p, String n) { pkg = p; name = n; }
    public String flattenToShortString() { return pkg + "/" + name; }
}
""",
    "android/content/Intent.java": """
package android.content;
public class Intent {
    public final String action;
    public ComponentName component;
    public Intent(String a) { action = a; }
    public Intent setComponent(ComponentName c) { component = c; return this; }
}
""",
    "android/content/Context.java": """
package android.content;
import android.content.pm.PackageManager;
import java.util.ArrayList;
public class Context {
    public final PackageManager pm = new PackageManager();
    public final ArrayList<Intent> broadcasts = new ArrayList<>();
    public float peak = 60;
    public PackageManager getPackageManager() { return pm; }
    public Context getContentResolver() { return this; }
    public void sendBroadcast(Intent i) { broadcasts.add(i); }
}
""",
    "android/content/pm/PackageManager.java": """
package android.content.pm;
import android.content.ComponentName;
import java.util.*;
public class PackageManager {
    public static final int MATCH_DISABLED_COMPONENTS = 512;
    public static final int COMPONENT_ENABLED_STATE_ENABLED = 1;
    public static final int COMPONENT_ENABLED_STATE_DISABLED = 2;
    public static final int DONT_KILL_APP = 1;
    public final Set<String> installed = new HashSet<>();
    public final Map<String,Integer> states = new HashMap<>();
    public String failPackage;
    public int updates;
    public Object getActivityInfo(ComponentName c, int flags) throws NameNotFoundException {
        if (flags != MATCH_DISABLED_COMPONENTS) throw new AssertionError("disabled entry lookup");
        if (!installed.contains(c.pkg)) throw new NameNotFoundException();
        if (c.pkg.equals(failPackage)) throw new IllegalStateException("package manager failed");
        return null;
    }
    public Object getReceiverInfo(ComponentName c, int flags) throws NameNotFoundException {
        return getActivityInfo(c, flags);
    }
    public int getComponentEnabledSetting(ComponentName c) {
        return states.getOrDefault(c.name, 0);
    }
    public void setComponentEnabledSetting(ComponentName c, int state, int flags) {
        if (flags != DONT_KILL_APP) throw new AssertionError("must not kill helper apps");
        states.put(c.name, state);
        updates++;
    }
    public static class NameNotFoundException extends Exception {}
}
""",
    "android/provider/Settings.java": """
package android.provider;
import android.content.Context;
public class Settings {
    public static class System {
        public static final String PEAK_REFRESH_RATE = "peak_refresh_rate";
        public static float getFloat(Context c, String key, float fallback) { return c.peak; }
    }
}
""",
    "android/util/Log.java": """
package android.util;
public class Log { public static int w(String t, String m, Throwable e) { return 0; } }
""",
    "org/andr36oid/gamecomfort/BootChecksTest.java": """
package org.andr36oid.gamecomfort;
import android.content.*;
import java.io.File;
import java.nio.file.*;
import java.util.Arrays;
public class BootChecksTest {
    private static int checks;
    private static void check(String name, boolean ok) {
        if (!ok) throw new AssertionError(name);
        checks++;
    }
    private static void state(Context c, String pkg, int expected) {
        check(pkg + " state " + expected, Integer.valueOf(expected).equals(c.pm.states.get(pkg + "." +
            (pkg.endsWith("statuslight") ? "StatusLightActivity" : pkg.endsWith("rgblights") ? "RgbLightsActivity" : "BfiActivity"))));
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        Context c = new Context();
        String status = "org.andr36oid.statuslight", rgb = "org.andr36oid.rgblights";
        String bfi = "org.andr36oid.bfi";
        c.pm.installed.addAll(Arrays.asList(status, rgb, bfi, "org.andr36oid.cardcheck"));
        Path colors = root.toPath().resolve("sys/devices/platform/andr36oid-status-led/colors");
        Files.createDirectories(colors.getParent());
        Files.write(colors, "green red\\n".getBytes("UTF-8"));
        BootChecks.run(c, root);
        state(c, status, 1); state(c, rgb, 2); state(c, bfi, 2);
        check("no progress means no card-check process", c.broadcasts.isEmpty());
        int updates = c.pm.updates;
        BootChecks.run(c, root);
        check("unchanged components are not rewritten", c.pm.updates == updates);

        Files.delete(colors);
        Files.createDirectories(root.toPath().resolve("sys/class/leds/red:status"));
        c.peak = 99.5f;
        BootChecks.run(c, root);
        state(c, status, 2); state(c, rgb, 1); state(c, bfi, 1);
        c.peak = 99.49f;
        BootChecks.run(c, root);
        state(c, bfi, 2);

        Path progress = root.toPath().resolve("data/misc/andr36oid-cardcheck");
        Files.createDirectories(progress.resolve("progress-directory"));
        Files.write(progress.resolve("result-full-ABC"), new byte[] {1});
        Files.write(progress.resolve("progress-empty"), new byte[0]);
        check("empty, unrelated files and directories ignored", !BootChecks.hasCardProgress(progress.toFile()));
        Files.write(progress.resolve("progress-ABC"), "written_mb=16\\n".getBytes("UTF-8"));
        c.pm.installed.remove(rgb);
        c.pm.failPackage = bfi;
        BootChecks.run(c, root);
        check("optional app and one failed check do not prevent recovery", c.broadcasts.size() == 1);
        Intent reminder = c.broadcasts.get(0);
        check("reminder action", reminder.action.equals("org.andr36oid.cardcheck.RESTORE_PAUSED"));
        check("only the card-check receiver is called", reminder.component.flattenToShortString().equals(
                "org.andr36oid.cardcheck/org.andr36oid.cardcheck.BootReceiver"));
        Files.delete(progress.resolve("progress-ABC"));
        BootChecks.run(c, root);
        check("completed/deleted check no longer starts reminder app", c.broadcasts.size() == 1);
        android.os.SystemProperties.values.put("persist.andr36oid.ram.boot_statuslight", "0");
        BootChecks.run(c, root);
        check("opt-out enables legacy receiver", Integer.valueOf(1).equals(c.pm.states.get(status + ".BootReceiver")));
        int sends = c.broadcasts.size();
        BootChecks.run(c, root);
        check("legacy boot resend only on transition", c.broadcasts.size() == sends);
        android.os.SystemProperties.values.clear();
        BootChecks.run(c, root);
        check("opt-in disables legacy receiver", Integer.valueOf(2).equals(c.pm.states.get(status + ".BootReceiver")));
        c.pm.failPackage = null;
        for (String helper : new String[] {"bfi", "rgblights", "cardcheck", "storagecare"}) {
            final String pkg = "org.andr36oid." + helper;
            c.pm.installed.add(pkg);
            final String receiver = pkg + (helper.equals("cardcheck")
                    ? ".BootStartReceiver" : ".BootReceiver");
            final String prop = "persist.andr36oid.ram.boot_" + helper;
            android.os.SystemProperties.values.put(prop, "0");
            BootChecks.run(c, root);
            check(helper + " independent opt-out restores Java receiver",
                    Integer.valueOf(1).equals(c.pm.states.get(receiver)));
            android.os.SystemProperties.values.remove(prop);
            if (!helper.equals("storagecare")) {
                BootChecks.run(c, root);
                check(helper + " opt-in removes legacy boot start",
                        Integer.valueOf(2).equals(c.pm.states.get(receiver)));
            }
        }
        String sync = "org.andr36oid.syncsettings";
        c.pm.installed.add(sync);
        BootChecks.run(c, root);
        check("sync hidden before subsystem first use", Integer.valueOf(2).equals(
                c.pm.states.get(sync + ".SyncSettingsActivity")));
        android.os.SystemProperties.values.put("persist.andr36oid.subsystem.booted", "1");
        BootChecks.run(c, root);
        check("sync visible after subsystem first use", Integer.valueOf(1).equals(
                c.pm.states.get(sync + ".SyncSettingsActivity")));
        android.os.SystemProperties.values.put("persist.andr36oid.ram.boot_syncsettings", "0");
        BootChecks.run(c, root);
        check("sync independent opt-out restores receiver", Integer.valueOf(1).equals(
                c.pm.states.get(sync + ".BootStartReceiver")));
        android.os.SystemProperties.values.remove("persist.andr36oid.ram.boot_syncsettings");
        BootChecks.run(c, root);
        check("sync opt-in disables redundant boot receiver", Integer.valueOf(2).equals(
                c.pm.states.get(sync + ".BootStartReceiver")));
        System.out.println("Boot checks: " + checks + " passed");
    }
}
""",
}

with tempfile.TemporaryDirectory(prefix="bootchecks-") as temp:
    root = Path(temp)
    files = []
    for name, content in stubs.items():
        dest = root / "src" / name
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(content)
        files.append(str(dest))
    subprocess.run([str(jdk / "javac"), "-source", "8", "-target", "8", "-Xlint:-options",
                    "-d", str(root / "classes"),
                    str(app / "src/org/andr36oid/gamecomfort/BootChecks.java"), *files], check=True)
    subprocess.run([str(jdk / "java"), "-cp", str(root / "classes"),
                    "org.andr36oid.gamecomfort.BootChecksTest", str(root / "board")], check=True)

# The cross-package explicit broadcast is private and works through the shared system UID.
ns = "{http://schemas.android.com/apk/res/android}"
for name in ["GameComfort", "CardCheck", "StatusLight", "RgbLights", "BlackFrameInsertion", "SyncSettings"]:
    manifest = ET.parse(app.parent / name / "AndroidManifest.xml").getroot()
    assert manifest.get(ns + "sharedUserId") == "android.uid.system", name
    if name in ["StatusLight", "RgbLights", "BlackFrameInsertion"]:
        receiver=next(r for r in manifest.iter("receiver") if r.get(ns+"name")==".BootReceiver")
        assert receiver.get(ns+"enabled")=="false", name
    if name in ["CardCheck", "SyncSettings"]:
        receiver=next(r for r in manifest.iter("receiver") if r.get(ns+"name")==".BootReceiver")
        assert receiver.get(ns+"exported")=="false" and receiver.find("intent-filter") is None
        fallback=next(r for r in manifest.iter("receiver") if r.get(ns+"name")==".BootStartReceiver")
        assert fallback.get(ns+"enabled")=="false"
print("Boot receiver manifests: passed")
