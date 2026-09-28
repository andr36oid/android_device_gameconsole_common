LOCAL_PATH := $(call my-dir)

# Extra apps for beta, dirty and debug builds, never in release builds (see
# device.mk). Shipped as plain files and installed as normal apps on first boot
# by the emulator preinstaller (emulators/seed/gameconsole-emu.sh), which reads
# beta.list next to them.

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-beta-apps
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := beta.list
LOCAL_SRC_FILES := beta.list
LOCAL_REQUIRED_MODULES := gameconsole-emu TrebleInfo-preinstall CPU-Z-preinstall UsbDeviceInfo-preinstall F-Droid-preinstall QuickShortcutMaker-preinstall Haven-preinstall Termux-preinstall
include $(BUILD_PREBUILT)

# Treble Info by Kevin Tresuelo (GPL-3.0), shows the Treble/GSI details of the device
include $(CLEAR_VARS)
LOCAL_MODULE := TrebleInfo-preinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := TrebleInfo.apk
LOCAL_SRC_FILES := TrebleInfo.apk
include $(BUILD_PREBUILT)

# CPU-Z by CPUID (freeware), shows the CPU, SoC, memory and sensors
include $(CLEAR_VARS)
LOCAL_MODULE := CPU-Z-preinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := CPU-Z.apk
LOCAL_SRC_FILES := CPU-Z.apk
include $(BUILD_PREBUILT)

# USB Device Info by alt236 (Apache-2.0, from F-Droid), lists attached USB devices
include $(CLEAR_VARS)
LOCAL_MODULE := UsbDeviceInfo-preinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := UsbDeviceInfo.apk
LOCAL_SRC_FILES := UsbDeviceInfo.apk
include $(BUILD_PREBUILT)

# F-Droid (GPL-3.0-or-later), the free and open source app store
include $(CLEAR_VARS)
LOCAL_MODULE := F-Droid-preinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := F-Droid.apk
LOCAL_SRC_FILES := F-Droid.apk
include $(BUILD_PREBUILT)

# QuickShortcutMaker by sika524 (freeware), makes shortcuts to any activity of an app
include $(CLEAR_VARS)
LOCAL_MODULE := QuickShortcutMaker-preinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := QuickShortcutMaker.apk
LOCAL_SRC_FILES := QuickShortcutMaker.apk
include $(BUILD_PREBUILT)

# Haven by GlassHaven (AGPL-3.0), SSH/Mosh/SFTP/SMB/VNC client; the smaller "terminal" build without RDP, Wayland desktop and ffmpeg
include $(CLEAR_VARS)
LOCAL_MODULE := Haven-preinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := Haven.apk
LOCAL_SRC_FILES := Haven.apk
include $(BUILD_PREBUILT)

# Termux (GPL-3.0, from F-Droid), a Linux terminal with its own package manager
include $(CLEAR_VARS)
LOCAL_MODULE := Termux-preinstall
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/preinstall
LOCAL_MODULE_STEM := Termux.apk
LOCAL_SRC_FILES := Termux.apk
include $(BUILD_PREBUILT)
