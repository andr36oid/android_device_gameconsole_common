/*
 * Copyright (C) 2013 The Android-x86 Open Source Project
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

#ifndef _BDROID_BUILDCFG_H
#define _BDROID_BUILDCFG_H

// At present either USB or UART is supported
#define BLUETOOTH_HCI_USE_USB          TRUE

// Bluetooth Low Power Mode is supported on BT4.0
#define HCILP_INCLUDED                 FALSE
#define KERNEL_MISSING_CLOCK_BOOTTIME_ALARM TRUE

/* BLE */
#define BTA_GATT_INCLUDED  TRUE
#define BLE_INCLUDED       TRUE
#define SMP_INCLUDED       TRUE

/* Game pads, sniff subrating: a pad whose SDP record has no
 * HIDSSRHostMaxLatency gets half the link supervision timeout, capped at
 * 800 slots (500 ms). While a pad that only reports on change is held still,
 * the link may then sleep up to 500 ms between polls and the next press waits
 * for it. Cap it at 18 slots (11.25 ms), the value the stack already uses for
 * Switch pads (INTEROP_HID_HOST_LIMIT_SNIFF_INTERVAL). */
#define BTA_HH_SSR_MAX_LATENCY_DEF 18

/* HID devices whose class of device does not say joystick or gamepad (some
 * pads in X-input or keyboard mode, cheap clones) got the generic sniff
 * interval of 30-54 slots (19-34 ms) 30 s after connecting, so at most ~30
 * reports per second. Give every HID device the gamepad interval, 14-18 slots
 * (9-11 ms). Mice and keyboards poll faster too, at a small cost to their
 * battery. */
#define BTA_DM_PM_SNIFF_HH_OPEN_IDX BTA_DM_PM_SNIFF6
#define BTA_DM_PM_SNIFF_HH_ACTIVE_IDX BTA_DM_PM_SNIFF6
#define BTA_DM_PM_SNIFF_HH_IDLE_IDX BTA_DM_PM_SNIFF6
#endif
