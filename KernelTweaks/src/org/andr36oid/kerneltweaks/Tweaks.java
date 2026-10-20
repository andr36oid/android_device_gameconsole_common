package org.andr36oid.kerneltweaks;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

/** Every tweak, by category, in the order of the page. Keys match andr36oid-ktweaks.sh. */
final class Tweaks {

    static final class Category {
        final int title;
        /** a link to another settings page at the top of the category, or null */
        final String linkPackage;
        final String linkActivity;
        final int linkTitle;
        final int linkSummary;
        final List<Tweak> tweaks = new ArrayList<>();

        Category(int title, String linkPackage, String linkActivity, int linkTitle,
                int linkSummary) {
            this.title = title;
            this.linkPackage = linkPackage;
            this.linkActivity = linkActivity;
            this.linkTitle = linkTitle;
            this.linkSummary = linkSummary;
        }

        Category add(Tweak t) {
            // every tweak shows its risk; one without would look as safe as anything
            if (t.level < Tweak.SAFE || t.level > Tweak.RISKY) {
                throw new IllegalStateException("Kernel Tweaks: no risk level for " + t.key);
            }
            tweaks.add(t);
            return this;
        }
    }

    static final int[] AXIS_NAMES = {
        R.string.kt_axis_x, R.string.kt_axis_y, R.string.kt_axis_rx, R.string.kt_axis_ry,
    };

    private static final String BOOL01_ON = "1";

    static List<Category> all() {
        final List<Category> list = new ArrayList<>();

        list.add(new Category(R.string.kt_cat_sleep,
                "org.andr36oid.deepsleep", "org.andr36oid.deepsleep.DeepSleepActivity",
                R.string.kt_link_deep_sleep, R.string.kt_link_deep_sleep_summary)
            .add(Tweak.choice("sleep_type", R.string.kt_sleep_type,
                    R.string.kt_sleep_type_what, R.string.kt_sleep_type_why,
                    "/sys/power/mem_sleep")
                .values("deep", "s2idle")
                .labels(R.string.kt_sleep_type_deep, R.string.kt_sleep_type_s2idle)
                .level(Tweak.LAST_RESORT))
            .add(Tweak.choice("sleep_test", R.string.kt_sleep_test,
                    R.string.kt_sleep_test_what, R.string.kt_sleep_test_why,
                    "/sys/power/pm_test")
                .values("none", "freezer", "devices", "platform", "processors", "core")
                .labels(R.string.kt_sleep_test_none, R.string.kt_sleep_test_freezer,
                        R.string.kt_sleep_test_devices, R.string.kt_sleep_test_platform,
                        R.string.kt_sleep_test_processors, R.string.kt_sleep_test_core)
                .once()
                .level(Tweak.RISKY))
            .add(Tweak.choice("sleep_console", R.string.kt_sleep_console,
                    R.string.kt_sleep_console_what, R.string.kt_sleep_console_why,
                    "/sys/module/printk/parameters/console_suspend")
                .values("Y", "N")
                .labels(R.string.kt_sleep_console_y, R.string.kt_sleep_console_n)
                .level(Tweak.SAFE))
            .add(onOff("sleep_log_times", R.string.kt_sleep_log_times,
                    R.string.kt_sleep_log_times_what, R.string.kt_sleep_log_times_why,
                    "/sys/power/pm_print_times")
                .level(Tweak.SAFE))
            .add(onOff("sleep_log_debug", R.string.kt_sleep_log_debug,
                    R.string.kt_sleep_log_debug_what, R.string.kt_sleep_log_debug_why,
                    "/sys/power/pm_debug_messages")
                .level(Tweak.SAFE)));

        list.add(new Category(R.string.kt_cat_cpu,
                "org.andr36oid.cpuoverclock", "org.andr36oid.cpuoverclock.OverclockActivity",
                R.string.kt_link_performance, R.string.kt_link_performance_summary)
            .add(Tweak.choice("cores", R.string.kt_cores, R.string.kt_cores_what,
                    R.string.kt_cores_why, "/sys/devices/system/cpu/cpu1-3/online")
                .values("4", "3", "2", "1")
                .labels(0, 0, 0, R.string.kt_one_core)
                .unit(R.string.kt_unit_cores)
                .level(Tweak.LAST_RESORT))
            .add(Tweak.choice("heat_limit", R.string.kt_heat_limit,
                    R.string.kt_heat_limit_what, R.string.kt_heat_limit_why,
                    "/sys/class/thermal/thermal_zone*/trip_point_1_temp (soc-thermal)")
                .values("75000", "80000", "85000", "90000", "95000")
                .unit(R.string.kt_unit_c)
                .scale(1000)
                .level(Tweak.RISKY))
            .add(onOff("perf_metrics_off", R.string.kt_perf_metrics_off,
                    R.string.kt_perf_metrics_off_what, R.string.kt_perf_metrics_off_why,
                    "sys.andr36oid.perf_metrics_off")
                .systemSetting()
                .level(Tweak.SAFE)));

        list.add(new Category(R.string.kt_cat_power, null, null, 0, 0)
            .add(Tweak.choice("dc_det", R.string.kt_dc_det,
                    R.string.kt_dc_det_what, R.string.kt_dc_det_why,
                    "/sys/module/rk817_charger/parameters/dc_det_mode")
                .values("auto", "pin", "power")
                .labels(R.string.kt_dc_det_auto, R.string.kt_dc_det_pin,
                        R.string.kt_dc_det_power)
                .level(Tweak.LAST_RESORT))
            .add(Tweak.choice("input_nearest_step", R.string.kt_input_nearest_step,
                    R.string.kt_input_nearest_step_what, R.string.kt_input_nearest_step_why,
                    "/sys/module/rk817_charger/parameters/input_nearest_step")
                .values("N", "Y")
                .labels(R.string.kt_off, R.string.kt_on)
                .level(Tweak.RISKY)));

        list.add(new Category(R.string.kt_cat_memory, null, null, 0, 0)
            .add(Tweak.choice("swappiness", R.string.kt_swappiness,
                    R.string.kt_swappiness_what, R.string.kt_swappiness_why,
                    "/proc/sys/vm/swappiness")
                .values("0", "30", "60", "100")
                .level(Tweak.SAFE))
            .add(Tweak.choice("cache_pressure", R.string.kt_cache_pressure,
                    R.string.kt_cache_pressure_what, R.string.kt_cache_pressure_why,
                    "/proc/sys/vm/vfs_cache_pressure")
                .values("50", "100", "200")
                .level(Tweak.SAFE))
            .add(Tweak.choice("page_cluster", R.string.kt_page_cluster,
                    R.string.kt_page_cluster_what, R.string.kt_page_cluster_why,
                    "/proc/sys/vm/page-cluster")
                .values("0", "1", "3")
                .labels(R.string.kt_pages_1, R.string.kt_pages_2, R.string.kt_pages_8)
                .level(Tweak.SAFE)));

        list.add(new Category(R.string.kt_cat_storage, null, null, 0, 0)
            .add(scheduler("sd1_scheduler", R.string.kt_sd1_scheduler, "mmcblk0")
                .level(Tweak.SAFE))
            .add(readahead("sd1_readahead", R.string.kt_sd1_readahead, "mmcblk0")
                .level(Tweak.SAFE))
            .add(scheduler("sd2_scheduler", R.string.kt_sd2_scheduler, "mmcblk1")
                .level(Tweak.SAFE))
            .add(readahead("sd2_readahead", R.string.kt_sd2_readahead, "mmcblk1")
                .level(Tweak.SAFE)));

        list.add(new Category(R.string.kt_cat_input, null, null, 0, 0)
            .add(Tweak.axes("stick_invert", R.string.kt_stick_invert,
                    R.string.kt_stick_invert_what, R.string.kt_stick_invert_why,
                    "/sys/devices/platform/odroidgo3-joypad/stick_invert")
                .level(Tweak.LAST_RESORT))
            .add(Tweak.choice("stick_swap", R.string.kt_stick_swap,
                    R.string.kt_stick_swap_what, R.string.kt_stick_swap_why,
                    "/sys/devices/platform/odroidgo3-joypad/stick_swap")
                .values("0", "1")
                .labels(R.string.kt_stick_swap_off, R.string.kt_stick_swap_on)
                .level(Tweak.LAST_RESORT))
            .add(Tweak.choice("poll_interval", R.string.kt_poll_interval,
                    R.string.kt_poll_interval_what, R.string.kt_poll_interval_why,
                    "/sys/devices/platform/odroidgo3-joypad/poll_interval")
                .values("4", "8", "10", "16", "20")
                .unit(R.string.kt_unit_ms)
                .level(Tweak.SAFE))
            .add(Tweak.choice("hp_det_flip", R.string.kt_hp_det_flip,
                    R.string.kt_hp_det_flip_what, R.string.kt_hp_det_flip_why,
                    "/sys/module/snd_soc_simple_card_utils/parameters/hp_det_flip")
                .values("N", "Y")
                .labels(R.string.kt_hp_det_normal, R.string.kt_hp_det_flipped)
                .level(Tweak.LAST_RESORT)));

        // Kernel command line options, through BOOT/kernel-tweaks.txt (boot.ini adds it)
        list.add(new Category(R.string.kt_cat_boot, null, null, 0, 0)
            .add(Tweak.choice("serial_console", R.string.kt_serial_console,
                    R.string.kt_serial_console_what, R.string.kt_serial_console_why,
                    "console=ttyS2,115200n8 fiq_debugger.disable=0")
                .values("off", "on")
                .labels(R.string.kt_off, R.string.kt_on)
                .atBoot()
                .level(Tweak.RISKY))
            .add(Tweak.choice("boot_log_level", R.string.kt_boot_log_level,
                    R.string.kt_boot_log_level_what, R.string.kt_boot_log_level_why,
                    "loglevel=")
                .values("4", "7", "8")
                .labels(R.string.kt_log_level_4, R.string.kt_log_level_7,
                        R.string.kt_log_level_8)
                .atBoot()
                .level(Tweak.SAFE))
            .add(Tweak.choice("cma_size", R.string.kt_cma_size,
                    R.string.kt_cma_size_what, R.string.kt_cma_size_why, "cma=")
                .values("default", "64", "96", "128", "192")
                .labels(R.string.kt_cma_default, 0, 0, 0, 0)
                .unit(R.string.kt_unit_mb)
                .atBoot()
                .level(Tweak.RISKY))
            .add(Tweak.choice("cpu_idle", R.string.kt_cpu_idle,
                    R.string.kt_cpu_idle_what, R.string.kt_cpu_idle_why, "cpuidle.off=1")
                .values("on", "off")
                .labels(R.string.kt_on, R.string.kt_off)
                .atBoot()
                .level(Tweak.LAST_RESORT))
            .add(Tweak.choice("timer_tick", R.string.kt_timer_tick,
                    R.string.kt_timer_tick_what, R.string.kt_timer_tick_why, "nohz=off")
                .values("on", "off")
                .labels(R.string.kt_timer_tick_idle, R.string.kt_timer_tick_always)
                .atBoot()
                .level(Tweak.SAFE))
            .add(Tweak.choice("boot_timing", R.string.kt_boot_timing,
                    R.string.kt_boot_timing_what, R.string.kt_boot_timing_why,
                    "initcall_debug")
                .values("0", BOOL01_ON)
                .labels(R.string.kt_off, R.string.kt_on)
                .atBoot()
                .level(Tweak.SAFE))
            .add(Tweak.choice("usb_autosuspend", R.string.kt_usb_autosuspend,
                    R.string.kt_usb_autosuspend_what, R.string.kt_usb_autosuspend_why,
                    "usbcore.autosuspend=-1")
                .values("on", "off")
                .labels(R.string.kt_on, R.string.kt_off)
                .atBoot()
                .level(Tweak.SAFE))
            .add(Tweak.choice("radios", R.string.kt_radios,
                    R.string.kt_radios_what, R.string.kt_radios_why, "module.no_radios=1")
                .values("on", "off")
                .labels(R.string.kt_on, R.string.kt_off)
                .atBoot()
                .level(Tweak.LAST_RESORT))
            .add(Tweak.choice("storage", R.string.kt_storage,
                    R.string.kt_storage_what, R.string.kt_storage_why,
                    "androidboot.storage_fuse=1")
                .values("sdcardfs", "fuse")
                .labels(R.string.kt_storage_sdcardfs, R.string.kt_storage_fuse)
                .atBoot()
                .level(Tweak.RISKY))
            .add(Tweak.choice("fuse", R.string.kt_fuse,
                    R.string.kt_fuse_what, R.string.kt_fuse_why, "fuse.disable=1")
                .values("on", "off")
                .labels(R.string.kt_on, R.string.kt_off)
                .atBoot()
                .level(Tweak.RISKY))
            .add(Tweak.choice("builtin_emmc", R.string.kt_builtin_emmc,
                    R.string.kt_builtin_emmc_what, R.string.kt_builtin_emmc_why,
                    "dw_mmc_rockchip.builtin_emmc=1")
                .values("off", "on")
                .labels(R.string.kt_off, R.string.kt_on)
                .atBoot()
                .level(Tweak.RISKY))
            .add(Tweak.choice("emmc_speed", R.string.kt_emmc_speed,
                    R.string.kt_emmc_speed_what, R.string.kt_emmc_speed_why,
                    "dw_mmc_rockchip.emmc_speed=")
                .values("hs", "ddr52", "hs200")
                .labels(R.string.kt_emmc_speed_hs, R.string.kt_emmc_speed_ddr52,
                        R.string.kt_emmc_speed_hs200)
                .atBoot()
                .level(Tweak.RISKY))
            .add(Tweak.choice("crash_log", R.string.kt_crash_log,
                    R.string.kt_crash_log_what, R.string.kt_crash_log_why,
                    "ramoops.disable=1")
                .values("on", "off")
                .labels(R.string.kt_on, R.string.kt_off)
                .atBoot()
                .level(Tweak.LAST_RESORT))
            .add(Tweak.choice("crash_split", R.string.kt_crash_split,
                    R.string.kt_crash_split_what, R.string.kt_crash_split_why,
                    "ramoops.record_size= ramoops.console_size= ramoops.pmsg_size=")
                .values("balanced", "kernel", "android")
                .labels(R.string.kt_crash_split_balanced, R.string.kt_crash_split_kernel,
                        R.string.kt_crash_split_android)
                .atBoot()
                .level(Tweak.SAFE)));

        list.add(new Category(R.string.kt_cat_debug, null, null, 0, 0)
            .add(Tweak.choice("log_level", R.string.kt_log_level,
                    R.string.kt_log_level_what, R.string.kt_log_level_why,
                    "/proc/sys/kernel/printk")
                .values("3", "4", "7", "8")
                .labels(R.string.kt_log_level_3, R.string.kt_log_level_4,
                        R.string.kt_log_level_7, R.string.kt_log_level_8)
                .level(Tweak.SAFE))
            .add(Tweak.choice("panic_restart", R.string.kt_panic_restart,
                    R.string.kt_panic_restart_what, R.string.kt_panic_restart_why,
                    "/proc/sys/kernel/panic")
                .values("0", "3", "7", "30")
                .labels(R.string.kt_panic_never, 0, 0, 0)
                .unit(R.string.kt_unit_s)
                .level(Tweak.SAFE))
            .add(onOff("oops_panic", R.string.kt_oops_panic, R.string.kt_oops_panic_what,
                    R.string.kt_oops_panic_why, "/proc/sys/kernel/panic_on_oops")
                .level(Tweak.RISKY))
            .add(Tweak.choice("stall_report", R.string.kt_stall_report,
                    R.string.kt_stall_report_what, R.string.kt_stall_report_why,
                    "/sys/module/rcupdate/parameters/rcu_cpu_stall_timeout")
                .values("10", "21", "60")
                .unit(R.string.kt_unit_s)
                .level(Tweak.SAFE))
            .add(onOff("crash_oops", R.string.kt_crash_oops, R.string.kt_crash_oops_what,
                    R.string.kt_crash_oops_why, "/sys/module/ramoops/parameters/dump_oops")
                .level(Tweak.SAFE))
            .add(Tweak.choice("crash_restart_log", R.string.kt_crash_restart_log,
                    R.string.kt_crash_restart_log_what, R.string.kt_crash_restart_log_why,
                    "/sys/module/printk/parameters/always_kmsg_dump")
                .values("N", "Y")
                .labels(R.string.kt_off, R.string.kt_on)
                .level(Tweak.SAFE))
            .add(onOff("power_chip_log", R.string.kt_power_chip_log,
                    R.string.kt_power_chip_log_what, R.string.kt_power_chip_log_why,
                    "/sys/module/{rk817_charger,rk817_battery,snd_soc_rk817}/parameters/dbg_level")
                .level(Tweak.SAFE)));

        return list;
    }

    private static Tweak onOff(String key, int title, int what, int why, String file) {
        return Tweak.choice(key, title, what, why, file)
            .values("0", BOOL01_ON)
            .labels(R.string.kt_off, R.string.kt_on);
    }

    private static Tweak scheduler(String key, int title, String dev) {
        return Tweak.choice(key, title, R.string.kt_scheduler_what, R.string.kt_scheduler_why,
                "/sys/block/" + dev + "/queue/scheduler")
            .values("mq-deadline", "none")
            .labels(R.string.kt_scheduler_deadline, R.string.kt_scheduler_none);
    }

    private static Tweak readahead(String key, int title, String dev) {
        return Tweak.choice(key, title, R.string.kt_readahead_what, R.string.kt_readahead_why,
                "/sys/block/" + dev + "/queue/read_ahead_kb")
            .values("128", "512", "1024", "2048")
            .unit(R.string.kt_unit_kb);
    }

    /** "0 0 1 0" as a flag per axis, null when it doesn't look like that */
    static boolean[] axes(String value) {
        if (value == null) return null;
        final String[] f = value.trim().split("\\s+");
        if (f.length != AXIS_NAMES.length) return null;
        final boolean[] out = new boolean[f.length];
        for (int i = 0; i < f.length; i++) out[i] = "1".equals(f[i]);
        return out;
    }

    static String axesValue(boolean[] flags) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < flags.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(flags[i] ? '1' : '0');
        }
        return sb.toString();
    }

    /** "Normal", or "Flipped: ..." for the axes that differ from the default */
    static String axesLabel(Context c, String value, String def) {
        final boolean[] v = axes(value);
        final boolean[] d = axes(def);
        if (v == null || d == null) return value;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (v[i] == d[i]) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.getString(AXIS_NAMES[i]));
        }
        return sb.length() == 0 ? c.getString(R.string.kt_stick_normal)
                : c.getString(R.string.kt_stick_flipped, sb);
    }
}
