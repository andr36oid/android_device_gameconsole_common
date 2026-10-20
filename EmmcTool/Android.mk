# The root part of Built-in eMMC: reads the eMMC's details, tests its read speed and copies
# it to image files, started by the app through ctl.start. The app itself is in Android.bp.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-emmc
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-emmc.sh
LOCAL_SRC_FILES := helper/andr36oid-emmc.sh
LOCAL_INIT_RC := helper/andr36oid-emmc.rc
LOCAL_REQUIRED_MODULES := andr36oid-emmcspeed
include $(BUILD_PREBUILT)
