LOCAL_PATH := $(call my-dir)

# The Magisk app, preinstalled so root management works offline from the first
# boot. Same APK the device mkimg.sh takes magiskinit and the payload from.
ifneq ($(wildcard $(LOCAL_PATH)/Magisk.apk),)
include $(CLEAR_VARS)
LOCAL_MODULE := MagiskApp
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)
LOCAL_BUILT_MODULE_STEM := package.apk
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_SRC_FILES := Magisk.apk
# A normal system app, so it can update itself
LOCAL_PRIVILEGED_MODULE := false
LOCAL_DEX_PREOPT := false
include $(BUILD_PREBUILT)
endif
