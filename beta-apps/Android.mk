LOCAL_PATH := $(call my-dir)

# Extra apps for beta, dirty and debug builds, never in release builds (see
# device.mk). Shipped as plain files in /system/etc/gameconsole/beta. Nothing
# installs them by itself: the Beta apps app (BetaApps/) reads beta.list next to
# them, asks the tester which ones they want and installs those as normal apps.

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-beta-apps
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := beta.list
LOCAL_SRC_FILES := beta.list
LOCAL_REQUIRED_MODULES := Andr36oidBetaApps TrebleInfo-beta CPU-Z-beta UsbDeviceInfo-beta F-Droid-beta QuickShortcutMaker-beta Haven-beta Termux-beta Athena-beta TotalCommander-beta TotalCommanderLAN-beta TotalCommanderSFTP-beta FreeOTP-beta Obtainium-beta R1HA-beta ActivityLauncher-beta ScreenStream-beta NewPipe-beta UniversalInstaller-beta
include $(BUILD_PREBUILT)

# Treble Info by Kevin Tresuelo (GPL-3.0), shows the Treble/GSI details of the device
include $(CLEAR_VARS)
LOCAL_MODULE := TrebleInfo-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := TrebleInfo.apk
LOCAL_SRC_FILES := TrebleInfo.apk
include $(BUILD_PREBUILT)

# CPU-Z by CPUID (freeware), shows the CPU, SoC, memory and sensors
include $(CLEAR_VARS)
LOCAL_MODULE := CPU-Z-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := CPU-Z.apk
LOCAL_SRC_FILES := CPU-Z.apk
include $(BUILD_PREBUILT)

# USB Device Info by alt236 (Apache-2.0, from F-Droid), lists attached USB devices
include $(CLEAR_VARS)
LOCAL_MODULE := UsbDeviceInfo-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := UsbDeviceInfo.apk
LOCAL_SRC_FILES := UsbDeviceInfo.apk
include $(BUILD_PREBUILT)

# F-Droid (GPL-3.0-or-later), the free and open source app store
include $(CLEAR_VARS)
LOCAL_MODULE := F-Droid-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := F-Droid.apk
LOCAL_SRC_FILES := F-Droid.apk
include $(BUILD_PREBUILT)

# QuickShortcutMaker by sika524 (freeware), makes shortcuts to any activity of an app
include $(CLEAR_VARS)
LOCAL_MODULE := QuickShortcutMaker-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := QuickShortcutMaker.apk
LOCAL_SRC_FILES := QuickShortcutMaker.apk
include $(BUILD_PREBUILT)

# Haven by GlassHaven (AGPL-3.0), SSH/Mosh/SFTP/SMB/VNC client; the smaller "terminal" build without RDP, Wayland desktop and ffmpeg
include $(CLEAR_VARS)
LOCAL_MODULE := Haven-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := Haven.apk
LOCAL_SRC_FILES := Haven.apk
include $(BUILD_PREBUILT)

# Termux (GPL-3.0, from F-Droid), a Linux terminal with its own package manager
include $(CLEAR_VARS)
LOCAL_MODULE := Termux-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := Termux.apk
LOCAL_SRC_FILES := Termux.apk
include $(BUILD_PREBUILT)

# Athena by SebaUbuntu (Apache-2.0, from F-Droid), shows detailed device and hardware info
include $(CLEAR_VARS)
LOCAL_MODULE := Athena-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := Athena.apk
LOCAL_SRC_FILES := Athena.apk
include $(BUILD_PREBUILT)

# Total Commander by Christian Ghisler (freeware), file manager
include $(CLEAR_VARS)
LOCAL_MODULE := TotalCommander-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := TotalCommander.apk
LOCAL_SRC_FILES := TotalCommander.apk
include $(BUILD_PREBUILT)

# Total Commander LAN plugin (freeware), Windows shares
include $(CLEAR_VARS)
LOCAL_MODULE := TotalCommanderLAN-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := TotalCommanderLAN.apk
LOCAL_SRC_FILES := TotalCommanderLAN.apk
include $(BUILD_PREBUILT)

# Total Commander SFTP plugin (freeware)
include $(CLEAR_VARS)
LOCAL_MODULE := TotalCommanderSFTP-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := TotalCommanderSFTP.apk
LOCAL_SRC_FILES := TotalCommanderSFTP.apk
include $(BUILD_PREBUILT)

# FreeOTP by Red Hat (Apache-2.0), two-factor codes
include $(CLEAR_VARS)
LOCAL_MODULE := FreeOTP-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := FreeOTP.apk
LOCAL_SRC_FILES := FreeOTP.apk
include $(BUILD_PREBUILT)

# Obtainium by ImranR98 (GPL-3.0), app updates straight from GitHub and other sources
include $(CLEAR_VARS)
LOCAL_MODULE := Obtainium-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := Obtainium.apk
LOCAL_SRC_FILES := Obtainium.apk
include $(BUILD_PREBUILT)

# R1HA by itskenny0 (Unlicense), Home Assistant client
include $(CLEAR_VARS)
LOCAL_MODULE := R1HA-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := R1HA.apk
LOCAL_SRC_FILES := R1HA.apk
include $(BUILD_PREBUILT)

# Activity Launcher (ISC), shortcuts to any app screen
include $(CLEAR_VARS)
LOCAL_MODULE := ActivityLauncher-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := ActivityLauncher.apk
LOCAL_SRC_FILES := ActivityLauncher.apk
include $(BUILD_PREBUILT)

# ScreenStream by Dmytro Kryvoruchko (MIT), shows the screen in a web browser
include $(CLEAR_VARS)
LOCAL_MODULE := ScreenStream-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := ScreenStream.apk
LOCAL_SRC_FILES := ScreenStream.apk
include $(BUILD_PREBUILT)

# NewPipe by Team NewPipe (GPL-3.0), YouTube and PeerTube player
include $(CLEAR_VARS)
LOCAL_MODULE := NewPipe-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := NewPipe.apk
LOCAL_SRC_FILES := NewPipe.apk
include $(BUILD_PREBUILT)


# Universal Installer by Nguyen Quang Minh (GPL-3.0), installs XAPK/APKS/APKM split apps
include $(CLEAR_VARS)
LOCAL_MODULE := UniversalInstaller-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_RELATIVE_PATH := gameconsole/beta
LOCAL_MODULE_STEM := UniversalInstaller.apk
LOCAL_SRC_FILES := UniversalInstaller.apk
include $(BUILD_PREBUILT)
