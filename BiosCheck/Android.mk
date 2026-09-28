# The root part of the BIOS check: scans EASYROMS and RetroArch's system folder and
# copies and renames files, started by the app through ctl.start. The app itself is
# in Android.bp.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-bioscheck
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-bioscheck.sh
LOCAL_SRC_FILES := helper/andr36oid-bioscheck.sh
LOCAL_INIT_RC := helper/andr36oid-bioscheck.rc
include $(BUILD_PREBUILT)
