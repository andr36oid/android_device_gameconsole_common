LOCAL_PATH := $(call my-dir)

# Extra apps for beta, dirty and debug builds, never in release builds (see
# device.mk). They go on the AUTOAPK partition with a .yml each, unticked, and
# the setup's app page offers them with the bundled apps (foss-apps/README.md).

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-beta-apps
LOCAL_REQUIRED_MODULES := TrebleInfo-beta UsbDeviceInfo-beta F-Droid-beta Haven-beta Athena-beta FreeOTP-beta Obtainium-beta R1HA-beta ActivityLauncher-beta ScreenStream-beta
include $(BUILD_PHONY_PACKAGE)

# Treble Info by Kevin Tresuelo (GPL-3.0), shows the Treble/GSI details of the device
include $(CLEAR_VARS)
LOCAL_MODULE := TrebleInfo-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := TrebleInfo.apk
LOCAL_SRC_FILES := TrebleInfo.apk
LOCAL_REQUIRED_MODULES := TrebleInfo-beta-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := TrebleInfo-beta-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := TrebleInfo.yml
LOCAL_SRC_FILES := TrebleInfo.yml
include $(BUILD_PREBUILT)

# USB Device Info by alt236 (Apache-2.0, from F-Droid), lists attached USB devices
include $(CLEAR_VARS)
LOCAL_MODULE := UsbDeviceInfo-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := UsbDeviceInfo.apk
LOCAL_SRC_FILES := UsbDeviceInfo.apk
LOCAL_REQUIRED_MODULES := UsbDeviceInfo-beta-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := UsbDeviceInfo-beta-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := UsbDeviceInfo.yml
LOCAL_SRC_FILES := UsbDeviceInfo.yml
include $(BUILD_PREBUILT)

# F-Droid (GPL-3.0-or-later), the free and open source app store
include $(CLEAR_VARS)
LOCAL_MODULE := F-Droid-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := F-Droid.apk
LOCAL_SRC_FILES := F-Droid.apk
LOCAL_REQUIRED_MODULES := F-Droid-beta-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := F-Droid-beta-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := F-Droid.yml
LOCAL_SRC_FILES := F-Droid.yml
include $(BUILD_PREBUILT)

# Haven by GlassHaven (AGPL-3.0), SSH/Mosh/SFTP/SMB/VNC client; the smaller "terminal" build without RDP, Wayland desktop and ffmpeg
include $(CLEAR_VARS)
LOCAL_MODULE := Haven-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Haven.apk
LOCAL_SRC_FILES := Haven.apk
LOCAL_REQUIRED_MODULES := Haven-beta-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := Haven-beta-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Haven.yml
LOCAL_SRC_FILES := Haven.yml
include $(BUILD_PREBUILT)

# Athena by SebaUbuntu (Apache-2.0, from F-Droid), shows detailed device and hardware info
include $(CLEAR_VARS)
LOCAL_MODULE := Athena-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Athena.apk
LOCAL_SRC_FILES := Athena.apk
LOCAL_REQUIRED_MODULES := Athena-beta-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := Athena-beta-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Athena.yml
LOCAL_SRC_FILES := Athena.yml
include $(BUILD_PREBUILT)

# FreeOTP by Red Hat (Apache-2.0), two-factor codes
include $(CLEAR_VARS)
LOCAL_MODULE := FreeOTP-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := FreeOTP.apk
LOCAL_SRC_FILES := FreeOTP.apk
LOCAL_REQUIRED_MODULES := FreeOTP-beta-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := FreeOTP-beta-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := FreeOTP.yml
LOCAL_SRC_FILES := FreeOTP.yml
include $(BUILD_PREBUILT)

# Obtainium by ImranR98 (GPL-3.0), app updates straight from GitHub and other sources
include $(CLEAR_VARS)
LOCAL_MODULE := Obtainium-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Obtainium.apk
LOCAL_SRC_FILES := Obtainium.apk
LOCAL_REQUIRED_MODULES := Obtainium-beta-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := Obtainium-beta-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := Obtainium.yml
LOCAL_SRC_FILES := Obtainium.yml
include $(BUILD_PREBUILT)

# R1HA by itskenny0 (Unlicense), Home Assistant client
include $(CLEAR_VARS)
LOCAL_MODULE := R1HA-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := R1HA.apk
LOCAL_SRC_FILES := R1HA.apk
LOCAL_REQUIRED_MODULES := R1HA-beta-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := R1HA-beta-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := R1HA.yml
LOCAL_SRC_FILES := R1HA.yml
include $(BUILD_PREBUILT)

# Activity Launcher (ISC), shortcuts to any app screen
include $(CLEAR_VARS)
LOCAL_MODULE := ActivityLauncher-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := ActivityLauncher.apk
LOCAL_SRC_FILES := ActivityLauncher.apk
LOCAL_REQUIRED_MODULES := ActivityLauncher-beta-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := ActivityLauncher-beta-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := ActivityLauncher.yml
LOCAL_SRC_FILES := ActivityLauncher.yml
include $(BUILD_PREBUILT)

# ScreenStream by Dmytro Kryvoruchko (MIT), shows the screen in a web browser
include $(CLEAR_VARS)
LOCAL_MODULE := ScreenStream-beta
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := ScreenStream.apk
LOCAL_SRC_FILES := ScreenStream.apk
LOCAL_REQUIRED_MODULES := ScreenStream-beta-yml
include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := ScreenStream-beta-yml
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/autoapk
LOCAL_MODULE_STEM := ScreenStream.yml
LOCAL_SRC_FILES := ScreenStream.yml
include $(BUILD_PREBUILT)
