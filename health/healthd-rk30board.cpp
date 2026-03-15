/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "healthd-odroidgoa"
#include <healthd/healthd.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <cutils/klog.h>
#include <sys/types.h>
#include <unistd.h>
#include <cstdlib>
#include <cmath>

#define PSU_SYSFS_PATH "/sys/class/power_supply/battery"
#define BATTERY_CRITICAL_LOW_CAP 30
#define BATTERY_CRITICAL_LOW_CURRENT_MA 100
#define BATTERY_MAX_CURRENT_MA        4000
#define BATTERY_CRITICAL_VOLTAGE_MV  3300  // 3.3V
#define BATTERY_FULL_VOLTAGE_MV      4200  // 4.2V

using namespace android;

static int read_sysfs_int(const char *path) {
    char buf[16] = {0};
    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        KLOG_ERROR(LOG_TAG, "Could not open '%s'\n", path);
        return -1;
    }

    ssize_t count = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (count <= 0)
        return -1;

    return atoi(buf);
}

static int read_current_ma() {
    int val = read_sysfs_int(PSU_SYSFS_PATH "/current_now");
    if (val == -1) return 0;
    return std::abs(val) / 1000; // µA -> mA
}

static int read_voltage_mv() {
    int val = read_sysfs_int(PSU_SYSFS_PATH "/voltage_now");
    if (val == -1) return 0;
    return val / 1000; // µV -> mV
}

static void odroidgoa_soc_adjust(struct BatteryProperties *props) {
    int soc = props->batteryLevel;

    if ((soc < BATTERY_CRITICAL_LOW_CAP) &&
        ((props->batteryStatus == BATTERY_STATUS_DISCHARGING) ||
         (props->batteryStatus == BATTERY_STATUS_NOT_CHARGING) ||
         (props->batteryStatus == BATTERY_STATUS_UNKNOWN))) {

        int current_ma = read_current_ma();
        int voltage_mv = read_voltage_mv();

        if (current_ma == 0 || voltage_mv == 0) {
            KLOG_WARNING(LOG_TAG, "current_now=%d voltage_now=%d\n", current_ma, voltage_mv);
        } else if (current_ma < BATTERY_CRITICAL_LOW_CURRENT_MA || voltage_mv < BATTERY_CRITICAL_VOLTAGE_MV) {
            soc = 0; // force shutdown
        } else {
            // scale SOC based on both current and voltage
            float current_scale = (float)current_ma / BATTERY_MAX_CURRENT_MA;
            float voltage_scale = (float)(voltage_mv - BATTERY_CRITICAL_VOLTAGE_MV) /
                                  (BATTERY_FULL_VOLTAGE_MV - BATTERY_CRITICAL_VOLTAGE_MV);
            voltage_scale = std::fmax(0.0f, std::fmin(1.0f, voltage_scale));

            soc = (int)(soc * current_scale * voltage_scale);
        }

        KLOG_INFO(LOG_TAG, "current=%d mA voltage=%d mV soc=%d\n", current_ma, voltage_mv, soc);
    }

    props->batteryLevel = soc;
}

int healthd_board_battery_update(struct BatteryProperties *props) {
    odroidgoa_soc_adjust(props);
    return 0;
}

void healthd_board_init(struct healthd_config *config) {}