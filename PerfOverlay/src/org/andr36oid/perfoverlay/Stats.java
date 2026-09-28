package org.andr36oid.perfoverlay;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Reads what the overlay shows. Everything comes from sysfs and procfs, except the frame
 * rate: SurfaceFlinger counts every frame it puts on the screen (its "page flips") and
 * hands the count out to the system user through debug transaction 1013, the same one
 * "dumpsys SurfaceFlinger" shows as "flips". Frames per second is that count's growth.
 */
final class Stats {

    private static final String TAG = "PerfOverlay";

    private static final String SF_DESCRIPTOR = "android.ui.ISurfaceComposer";
    private static final int SF_GET_PAGE_FLIP_COUNT = 1013;

    private static final String CPU_FREQ =
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq";

    /** -1 or NaN where a value couldn't be read. */
    int fps = -1;
    int cpuPercent = -1;
    int cpuMhz = -1;
    int gpuMhz = -1;
    float tempC = Float.NaN;
    int batteryPercent = -1;
    float batteryWatts = Float.NaN;
    boolean charging;
    int ramPercent = -1;

    private final String mGpuFreqPath = findGpuFreq();
    private final String mTempPath = findTemperature();
    private final String mBatteryPath = findBattery();

    private IBinder mSurfaceFlinger;
    private long mLastFlips = -1;
    private long mLastFlipTime;
    private long mLastCpuBusy = -1;
    private long mLastCpuTotal;

    /** Forget the last sample, e.g. after the screen was off. */
    void reset() {
        mLastFlips = -1;
        mLastCpuBusy = -1;
        fps = -1;
        cpuPercent = -1;
    }

    void sample(boolean everything) {
        sampleFps();
        if (!everything) {
            return;
        }
        sampleCpu();
        cpuMhz = readInt(CPU_FREQ, 1000);
        gpuMhz = mGpuFreqPath == null ? -1 : readInt(mGpuFreqPath, 1000000);
        final int milliC = mTempPath == null ? Integer.MIN_VALUE : readInt(mTempPath, 1);
        tempC = milliC == Integer.MIN_VALUE || milliC < 0 ? Float.NaN : milliC / 1000f;
        sampleBattery();
        sampleRam();
    }

    private void sampleFps() {
        final long flips = readPageFlips();
        final long now = SystemClock.elapsedRealtime();
        if (flips < 0) {
            fps = -1;
            return;
        }
        if (mLastFlips >= 0 && now > mLastFlipTime) {
            // The counter is a uint32 that may wrap.
            final long frames = (flips - mLastFlips) & 0xffffffffL;
            fps = Math.round(frames * 1000f / (now - mLastFlipTime));
        }
        mLastFlips = flips;
        mLastFlipTime = now;
    }

    private long readPageFlips() {
        if (mSurfaceFlinger == null) {
            mSurfaceFlinger = ServiceManager.getService("SurfaceFlinger");
            if (mSurfaceFlinger == null) {
                return -1;
            }
        }
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SF_DESCRIPTOR);
            if (!mSurfaceFlinger.transact(SF_GET_PAGE_FLIP_COUNT, data, reply, 0)) {
                return -1;
            }
            return reply.readInt() & 0xffffffffL;
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "Couldn't read SurfaceFlinger's frame count", e);
            mSurfaceFlinger = null;
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Busy share of all cores since the last sample, from the first line of /proc/stat. */
    private void sampleCpu() {
        final String stat = read("/proc/stat");
        if (stat == null || !stat.startsWith("cpu ")) {
            cpuPercent = -1;
            return;
        }
        final String[] fields = stat.substring(0, stat.indexOf('\n')).trim().split("\\s+");
        long total = 0;
        long idle = 0;
        // user nice system idle iowait irq softirq steal
        for (int i = 1; i < fields.length && i <= 8; i++) {
            final long value = Long.parseLong(fields[i]);
            total += value;
            if (i == 4 || i == 5) {
                idle += value;
            }
        }
        final long busy = total - idle;
        if (mLastCpuBusy >= 0 && total > mLastCpuTotal) {
            cpuPercent = (int) (100 * (busy - mLastCpuBusy) / (total - mLastCpuTotal));
        }
        mLastCpuBusy = busy;
        mLastCpuTotal = total;
    }

    private void sampleBattery() {
        if (mBatteryPath == null) {
            batteryPercent = -1;
            return;
        }
        batteryPercent = readInt(mBatteryPath + "capacity", 1);
        final String status = read(mBatteryPath + "status");
        charging = status != null && (status.startsWith("Charging") || status.startsWith("Full"));
        final int microAmps = readInt(mBatteryPath + "current_now", 1);
        final int microVolts = readInt(mBatteryPath + "voltage_now", 1);
        if (microAmps == Integer.MIN_VALUE || microVolts == Integer.MIN_VALUE) {
            batteryWatts = Float.NaN;
        } else {
            batteryWatts = Math.abs(microAmps / 1e6f * microVolts / 1e6f);
        }
    }

    private void sampleRam() {
        final String meminfo = read("/proc/meminfo");
        final long total = meminfoKb(meminfo, "MemTotal:");
        final long available = meminfoKb(meminfo, "MemAvailable:");
        ramPercent = total > 0 && available >= 0 ? (int) (100 * (total - available) / total) : -1;
    }

    private static long meminfoKb(String meminfo, String key) {
        if (meminfo == null) {
            return -1;
        }
        final int start = meminfo.indexOf(key);
        if (start < 0) {
            return -1;
        }
        final int end = meminfo.indexOf('\n', start);
        final String line = meminfo.substring(start + key.length(),
                end < 0 ? meminfo.length() : end).trim();
        try {
            return Long.parseLong(line.split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** cur_freq of the first devfreq device named like a GPU (ff400000.gpu on the RK3326). */
    private static String findGpuFreq() {
        final File[] devices = new File("/sys/class/devfreq").listFiles();
        if (devices == null) {
            return null;
        }
        for (File device : devices) {
            if (device.getName().contains("gpu")) {
                return device.getPath() + "/cur_freq";
            }
        }
        return null;
    }

    /** The SoC's thermal zone, or the first one there is. */
    private static String findTemperature() {
        final File[] zones = new File("/sys/class/thermal").listFiles(
                (dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null || zones.length == 0) {
            return null;
        }
        for (File zone : zones) {
            final String type = read(zone.getPath() + "/type");
            if (type != null && type.contains("soc")) {
                return zone.getPath() + "/temp";
            }
        }
        return zones[0].getPath() + "/temp";
    }

    private static String findBattery() {
        final File[] supplies = new File("/sys/class/power_supply").listFiles();
        if (supplies == null) {
            return null;
        }
        for (File supply : supplies) {
            if ("Battery".equals(read(supply.getPath() + "/type"))) {
                return supply.getPath() + "/";
            }
        }
        return null;
    }

    /** The number in the file divided by {@code divisor}, MIN_VALUE if unreadable. */
    private static int readInt(String path, int divisor) {
        final String text = read(path);
        if (text == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return (int) (Long.parseLong(text) / divisor);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
