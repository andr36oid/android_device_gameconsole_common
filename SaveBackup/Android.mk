# The root part of Save backup: finds the drives and runs the backup engine (from the
# app's APK) as root, started by the app through ctl.start. The app itself is in
# Android.bp.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := andr36oid-savebackup
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_STEM := andr36oid-savebackup.sh
LOCAL_SRC_FILES := helper/andr36oid-savebackup.sh
LOCAL_INIT_RC := helper/andr36oid-savebackup.rc
include $(BUILD_PREBUILT)
