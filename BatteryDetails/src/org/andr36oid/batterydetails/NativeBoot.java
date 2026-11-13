package org.andr36oid.batterydetails;

import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;

/** Boot work called in the existing consolidated helper, under this package's context. */
public final class NativeBoot {
    private NativeBoot() {}
    static boolean active() {
        return "1".equals(SystemProperties.get("persist.andr36oid.ram.boot_batterydetails", "0"))
                && "1".equals(SystemProperties.get("sys.andr36oid.ram.battery_sampler", "0"));
    }
    public static void prepare(Context context) {
        if (!active()) throw new IllegalStateException("Native battery sampler unavailable");
        // Context creates the files directory with the package's normal owner/label.
        context.getFilesDir();
        SampleJob.schedule(context); // cancels the old persisted sampler job
        // Capacity measurements retain the exact receiver/service/notification path.
        if (Measure.state(context) == Measure.RUNNING || Measure.state(context) == Measure.WAITING) {
            new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        }
    }
}
