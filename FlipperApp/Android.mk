LOCAL_PATH := $(call my-dir)

# Built by the standalone Gradle project; do not compile an emulator in the ROM.
ifneq ($(wildcard $(LOCAL_PATH)/prebuilt/FlipperEmulator-r36s.apk),)
include $(CLEAR_VARS)
LOCAL_MODULE := FlipperEmulator
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_SRC_FILES := prebuilt/FlipperEmulator-r36s.apk
LOCAL_CERTIFICATE := platform
LOCAL_DEX_PREOPT := false
LOCAL_REQUIRED_MODULES := UsbMode
include $(BUILD_PREBUILT)
endif
