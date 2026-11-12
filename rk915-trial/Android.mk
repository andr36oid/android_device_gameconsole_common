# Init-owned, disarmed RK915 checked-resume trial watchdog for an attended test image.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-rk915-trial-watchdog
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := prebuilt/andr36oid-rk915-trial-watchdog
LOCAL_STRIP_MODULE := false
LOCAL_CHECK_ELF_FILES := false
include $(BUILD_PREBUILT)
